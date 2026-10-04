// ==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause-Clear
//
// ==============================================================================

/**
 * @file qhpi_matcher.h

 * @brief Declarative pattern matcher for recognizing QHPI subgraphs.
 *
 * Recognizing a fusion candidate through the raw QHPI C API means hand-rolling
 * a recursive walk with bookkeeping for repeated operands and multi-output ops.
 * This header lets the same pattern be written as an expression tree that
 * mirrors the shape of the subgraph, then matched against a candidate root op
 * and collapsed into a single custom operator via qhpi_op_create().
 *
 * Usage is two-phase, and the split matters: a Matcher subclass builds its
 * terms once in its constructor, because the capturing factories -- any() and
 * the op factories -- each permanently allocate a binding slot. Matching then
 * runs per candidate root op, with reset() clearing bindings between attempts.
 *
 * @code
 *   struct MyPattern : public Matcher {
 *       MatchTerm in, root;
 *       MyPattern() { in = any(); root = QNN_Reshape(in); }
 *       bool operator()(const QHPI_Op *op) { reset(); return matches(root, op); }
 *   };
 * @endcode
 *
 * @see docs/moe/README.md for the MoE and attention patterns this implements.
 * @see qhpi.h for the underlying graph inspection API.
 */

#pragma once
#include "qhpi.h"
#include "float16.h"
#include <vector>
#include <array>
#include <type_traits>
#include <cassert>
#include <cmath>
#include <cstring>
#include <functional>
#include <limits>
#include <cstdio>
#include <cstdlib>
#include <string>

namespace qhpi {

class Matcher;

/**
 * @brief One node in a pattern expression graph.
 *
 * A term is either a leaf constraint on a single op (a constant, a specific
 * constant value, a wildcard) or an interior node naming an op and
 * constraining its inputs. Terms are built by the Matcher factories rather
 * than constructed directly, since the capturing kinds need a binding slot from
 * the owning Matcher.
 */
class MatchTerm {
    static constexpr unsigned NO_INDEX = std::numeric_limits<unsigned>::max();

  private:
    /** @brief What a term matches. Selects which union member is live. */
    enum class Kind {
        CONST, /**< Any constant op, whatever its value. */
        INTEGER, /**< Constant of any integer type whose every element equals @c ival. */
        REAL, /**< Float or quantized constant whose real value equals @c fval. */
        PATTERN, /**< Op named @c name whose inputs match @c inputs. */
        ANY, /**< Wildcard; captures whatever op it first sees. */
        NONE, /**< Default-constructed placeholder; matches nothing. */
        VARARGS, /**< Absorbs a variable-length run of inputs. */
        VERIFY, /**< Sub-match in @c inputs[0] plus a user @c predicate. */
        OUT, /**< Output number @c output_number of a multi-output op. */
        OUT_REF, /**< Output number read from @c *output_number_ptr at match time. */
        INT_REF, /**< Integer constant whose value is read from @c *int_ptr at match time. */
        ALTERNATIVE, /**< First of @c inputs that matches; binds nothing itself. */
    } kind;
    // ival/fval carry the expected value for the value-checking kinds;
    // output_number/output_number_ptr the selected output for OUT/OUT_REF;
    // int_ptr the expected value for INT_REF.
    union {
        int ival;
        float fval;
        unsigned *output_number_ptr;
        unsigned output_number;
        const unsigned *int_ptr;
    };
    // Slot in the owning Matcher's matches_ table, for the capturing kinds
    // (ANY, PATTERN, and VERIFY, which shares its sub-pattern's slot).
    // NO_INDEX for the rest, which bind nothing.
    unsigned index = NO_INDEX;
    std::function<bool(const QHPI_Op *)> predicate;
    const char *name = nullptr;
    std::vector<MatchTerm> inputs;

  public:
    MatchTerm() : kind(Kind::NONE) {}
    MatchTerm(Kind kind, unsigned index) : kind(kind), index(index) {}
    // VERIFY: sub-match in inputs[0], gated by a caller-supplied predicate.
    MatchTerm(std::function<bool(const QHPI_Op *)> predicate, unsigned index, std::vector<MatchTerm> &&inputs)
        : kind(Kind::VERIFY), index(index), predicate(predicate), inputs(inputs)
    {
    }
    // Separate integral and floating overloads: with only an integral one, a
    // float literal would convert silently and the term be misclassified as
    // INTEGER with a truncated value. Both float overloads narrow to float,
    // so a double literal is matched at float precision.
    MatchTerm(int val) : kind(Kind::INTEGER), ival(val) {}
    MatchTerm(unsigned val) : kind(Kind::INTEGER), ival(int(val)) {}
    MatchTerm(float val) : kind(Kind::REAL), fval(val) {}
    MatchTerm(double val) : kind(Kind::REAL), fval(float(val)) {}
    MatchTerm(unsigned index, const char *name, std::vector<MatchTerm> &&inputs)
        : kind(Kind::PATTERN), index(index), name(name), inputs(inputs)
    {
    }
    MatchTerm &operator=(const MatchTerm &t) = default;

    friend class Matcher;
};

/**
 * @brief Owns the binding table for one pattern and builds its terms.
 *
 * Subclass this, build terms in the constructor, and expose an
 * @c operator()(const QHPI_Op*) that resets and calls matches().
 */
class Matcher {
    // Binding table: slot per capturing term, indexed by MatchTerm::index.
    std::vector<const QHPI_Op *> matches_;
    // Nesting depth of the in-flight matches() recursion, for trace indenting.
    unsigned trace_depth_ = 0;
    // Deepest point the current attempt reached, versus trace_min_depth().
    unsigned trace_max_depth_ = 0;
    // Buffered trace of the current attempt, printed only if it goes deep.
    std::vector<std::string> trace_lines_;
    // Slots are handed out when terms are built, never during matching, which
    // is why a pattern must be constructed once and reused.
    unsigned next()
    {
        unsigned idx = matches_.size();
        matches_.push_back(nullptr);
        return idx;
    }

    /** @brief Human-readable name of a term kind, for traces. */
    static const char *kind_name(MatchTerm::Kind kind)
    {
        switch (kind) {
        case MatchTerm::Kind::CONST:
            return "const";
        case MatchTerm::Kind::INTEGER:
            return "int";
        case MatchTerm::Kind::REAL:
            return "real";
        case MatchTerm::Kind::PATTERN:
            return "op";
        case MatchTerm::Kind::ANY:
            return "any";
        case MatchTerm::Kind::NONE:
            return "none";
        case MatchTerm::Kind::VARARGS:
            return "varargs";
        case MatchTerm::Kind::VERIFY:
            return "verify";
        case MatchTerm::Kind::OUT:
            return "out";
        case MatchTerm::Kind::OUT_REF:
            return "out&";
        case MatchTerm::Kind::INT_REF:
            return "int&";
        case MatchTerm::Kind::ALTERNATIVE:
            return "alternative";
        }
        return "?";
    }

    /** @brief Describe what a term expects, e.g. `op q::QNN_MatMul` or `int 3`. */
    static void describe_term(const MatchTerm &t, char *buf, size_t size)
    {
        switch (t.kind) {
        case MatchTerm::Kind::PATTERN:
            snprintf(buf, size, "op %s", t.name ? t.name : "(null)");
            break;
        case MatchTerm::Kind::INTEGER:
            snprintf(buf, size, "int %d", t.ival);
            break;
        case MatchTerm::Kind::REAL:
            snprintf(buf, size, "real %g", double(t.fval));
            break;
        case MatchTerm::Kind::OUT:
            snprintf(buf, size, "out %u", t.output_number);
            break;
        case MatchTerm::Kind::OUT_REF:
            snprintf(buf, size, "out& %u", t.output_number_ptr ? *t.output_number_ptr : 0u);
            break;
        case MatchTerm::Kind::INT_REF:
            snprintf(buf, size, "int& %u", t.int_ptr ? *t.int_ptr : 0u);
            break;
        default:
            snprintf(buf, size, "%s", kind_name(t.kind));
            break;
        }
    }

    /** @brief Describe the candidate op: id, name, and value if it is a constant. */
    static void describe_op(const QHPI_Op *op, char *buf, size_t size)
    {
        if (op == nullptr) {
            snprintf(buf, size, "(null)");
            return;
        }
        // The id is printed in hex to match the "plugin attempt 0x..." lines the
        // compiler logs, so a traced term lines up with the engine's own log and
        // with a graph dump without any translation.
        const unsigned long long id = qhpi_op_id(op);
        const char *name = qhpi_op_name(op);
        if (not qhpi_op_is_constant(op)) {
            snprintf(buf, size, "0x%llx %s", id, name ? name : "(unnamed)");
            return;
        }
        // For constants the dtype and first element are what a value-checking
        // term actually compares against, so show both. A quantized element is
        // shown dequantized with its raw value alongside, since that is what a
        // float term is really being tested against.
        const QHPI_OutputDef out = qhpi_op_output(op, 0);
        const void *data = qhpi_op_constant_data(op);
        char val[64] = "?";
        if (data != nullptr) {
            const QHPI_Quant_Parameters q = out.quant_parameters;
            switch (out.type) {
            case QHPI_QUINT8:
                describe_quantized(val, sizeof(val), ((const uint8_t *)data)[0], q);
                break;
            case QHPI_QINT8:
                describe_quantized(val, sizeof(val), ((const int8_t *)data)[0], q);
                break;
            case QHPI_QUINT16:
                describe_quantized(val, sizeof(val), ((const uint16_t *)data)[0], q);
                break;
            case QHPI_QINT16:
                describe_quantized(val, sizeof(val), ((const int16_t *)data)[0], q);
                break;
            case QHPI_QINT32:
                describe_quantized(val, sizeof(val), ((const int32_t *)data)[0], q);
                break;
            case QHPI_INT32:
                snprintf(val, sizeof(val), "%d", ((const int32_t *)data)[0]);
                break;
            case QHPI_INT64:
                snprintf(val, sizeof(val), "%lld", (long long)((const int64_t *)data)[0]);
                break;
            case QHPI_FLOAT32:
                snprintf(val, sizeof(val), "%g", double(((const float *)data)[0]));
                break;
            case QHPI_FLOAT16:
                snprintf(val, sizeof(val), "%g",
                         double(float(hexnn_fp::float16_from_raw(((const uint16_t *)data)[0]))));
                break;
            default:
                break;
            }
        }
        snprintf(buf, size, "0x%llx %s dtype=%u val=%s", id, name ? name : "(unnamed)", unsigned(out.type), val);
    }

    /** @brief Render a quantized element as "real (raw N)", e.g. `0.5 (raw 129)`. */
    static void describe_quantized(char *buf, size_t size, int64_t raw, QHPI_Quant_Parameters q)
    {
        // A non-finite stepsize means the params are unusable. Note the
        // QHPI_Quant_Parameters_Invalid sentinel is FLT_MAX, which *is* finite,
        // so it prints as a real value rather than being caught here.
        if (not std::isfinite(q.stepsize)) {
            snprintf(buf, size, "raw %lld (no quant params)", (long long)raw);
            return;
        }
        const double real = double(raw - int64_t(q.zero_offset)) * double(q.stepsize);
        snprintf(buf, size, "%g (raw %lld)", real, (long long)raw);
    }

  public:
    enum BinaryOperator {
        ADD,
        AND,
        DIV,
        EQUAL,
        FLOORDIV,
        FMOD,
        GREATER,
        GREAT_EQ,
        LESS,
        LESS_EQ,
        MAX,
        MIN,
        MOD,
        MUL,
        NEQ,
        OR,
        POW,
        SQ_DIFF,
        SUB,
        XOR
    };

    Matcher() { matches_.reserve(32); }
    /** @brief Wildcard term that captures whatever op it matches. */
    MatchTerm any() { return MatchTerm(MatchTerm::Kind::ANY, next()); }
    /** @brief Placeholder absorbing a variable-length run of inputs. */
    MatchTerm varargs() { return MatchTerm(MatchTerm::Kind::VARARGS, MatchTerm::NO_INDEX); }
    /**
     * @brief Match @p a, then require @p predicate to accept the same op.
     *
     * The escape hatch for constraints the term language cannot express, such
     * as "these two ops share their constant operands".
     *
     * @param a         Sub-pattern the op must match first.
     * @param predicate Additional check run on the matched op.
     * @return A term combining both checks.
     */
    MatchTerm verify(MatchTerm a, std::function<bool(const QHPI_Op *)> predicate)
    {
        return MatchTerm(predicate, a.index, {a});
    }
    /** @brief Any constant op. Named with '$' to dodge the const keyword. */
    MatchTerm $const() { return MatchTerm(MatchTerm::Kind::CONST, MatchTerm::NO_INDEX); }
    /**
     * @brief Match the first of @p terms that accepts the op.
     *
     * For the small spelling differences between otherwise identical graphs --
     * an extra Reshape on an edge, a different activation -- where writing one
     * pattern per variant would duplicate the whole surrounding structure.
     * Alternatives are tried left to right, so put the common form first.
     *
     * Each attempt rolls the binding table back before the next one, which no
     * pattern in tree today needs: a PATTERN binds its own slot only after its
     * inputs match, so a branch that fails deep leaves its *children* bound, and
     * that is invisible unless a later branch needs one of those shared slots to
     * hold a different op. The existing alternatives all agree on their shared
     * captures, so with the rollback removed every graph in docs/moe/models.txt
     * still matches identically -- it is guarding a hazard the current patterns
     * do not reach, kept because what it prevents is a *wrong* match rather than
     * a missed one. Write an alternative whose branches disagree about a shared
     * capture and it starts to matter.
     *
     * Note this is not the same as relaxing the bind-or-compare conflict check in
     * matches_impl(): that check is what makes one term used twice mean "the same
     * op both times", which is what ties an MoE expert body to the tokens its
     * router scored. Rolling back a failed attempt preserves it; dropping it does
     * not.
     *
     * @param terms Two or more sub-patterns.
     * @return A term matching whichever of @p terms matches first.
     */
    template <typename... Terms> MatchTerm alternative(Terms... terms)
    {
        static_assert(sizeof...(Terms) > 1, "Matcher::alternative: expects at least two MatchTerms");
        MatchTerm a(MatchTerm::Kind::ALTERNATIVE, MatchTerm::NO_INDEX);
        a.inputs = {terms...};
        return a;
    }
    /**
     * @brief Select a fixed output number of a multi-output op.
     *
     * @param in            Sub-pattern for the producing op.
     * @param output_number Output number to select.
     * @return A term matching that one output.
     */
    MatchTerm out(MatchTerm in, unsigned output_number)
    {
        MatchTerm o(MatchTerm::Kind::OUT, MatchTerm::NO_INDEX);
        o.output_number = output_number;
        o.inputs.push_back(in);
        return o;
    }
    /**
     * @brief Select an output whose number is read at match time.
     *
     * Lets one term walk a different output on each pass over a repeated
     * pattern: the caller advances the pointed-to counter between passes.
     *
     * @param in                Sub-pattern for the producing op.
     * @param output_number_ref Points at the output number, read during matching.
     * @return A term matching the currently selected output.
     */
    MatchTerm out(MatchTerm in, unsigned *output_number_ref)
    {
        MatchTerm o(MatchTerm::Kind::OUT_REF, MatchTerm::NO_INDEX);
        o.output_number_ptr = output_number_ref;
        o.inputs.push_back(in);
        return o;
    }
    /**
     * @brief Match an integer constant whose value is read at match time.
     *
     * The INTEGER term's late-bound sibling, as out(term, unsigned*) is out()'s:
     * lets one term compare against a different value on each pass over a
     * repeated pattern, for a graph that spells a per-expert selector as a
     * literal operand rather than an output number.
     *
     * @param value_ref Points at the wanted value, read during matching.
     * @return A term matching a constant equal to the current value.
     */
    MatchTerm int_ref(const unsigned *value_ref)
    {
        MatchTerm t(MatchTerm::Kind::INT_REF, MatchTerm::NO_INDEX);
        t.int_ptr = value_ref;
        return t;
    }
    // Op factories. Each names a QNN op with its "q::" package prefix, takes
    // its inputs in QNN operand order, and claims a fresh binding slot.
    MatchTerm QNN_MatMul(MatchTerm a, MatchTerm b, MatchTerm bias, MatchTerm ta, MatchTerm tb)
    {
        return MatchTerm(next(), "q::QNN_MatMul", {a, b, bias, ta, tb});
    }
    MatchTerm QNN_ElementWiseBinary(MatchTerm a, MatchTerm b, MatchTerm operation)
    {
        return MatchTerm(next(), "q::QNN_ElementWiseBinary", {a, b, operation});
    }
    MatchTerm QNN_ReduceMin(MatchTerm a, MatchTerm dim, MatchTerm mode)
    {
        return MatchTerm(next(), "q::QNN_ReduceMin", {a, dim, mode});
    }
    MatchTerm QNN_ElementWiseSelect(MatchTerm p, MatchTerm a, MatchTerm b)
    {
        return MatchTerm(next(), "q::QNN_ElementWiseSelect", {p, a, b});
    }
    MatchTerm QNN_Softmax(MatchTerm p, MatchTerm a, MatchTerm b)
    {
        return MatchTerm(next(), "q::QNN_Softmax", {p, a, b});
    }
    MatchTerm QNN_Reshape(MatchTerm p) { return MatchTerm(next(), "q::QNN_Reshape", {p}); }

    MatchTerm QNN_Conv2d(MatchTerm in, MatchTerm w, MatchTerm b, MatchTerm in3, MatchTerm in4, MatchTerm in5,
                         MatchTerm in6, MatchTerm in7)
    {
        return MatchTerm(next(), "q::QNN_Conv2d", {in, w, b, in3, in4, in5, in6, in7});
    }
    // As QNN_Conv2d, plus a trailing per-output-channel weight scale. Quantized
    // models carry the weights and their scale as separate operands, so an
    // expert's weights are selected by *two* muxes rather than one.
    MatchTerm QNN_Conv2d_w_scale(MatchTerm in, MatchTerm w, MatchTerm b, MatchTerm in3, MatchTerm in4, MatchTerm in5,
                                 MatchTerm in6, MatchTerm in7, MatchTerm w_scale)
    {
        return MatchTerm(next(), "q::QNN_Conv2d_w_scale", {in, w, b, in3, in4, in5, in6, in7, w_scale});
    }
    MatchTerm QNN_TopK(MatchTerm in0, MatchTerm in1, MatchTerm in2)
    {
        return MatchTerm(next(), "q::QNN_TopK", {in0, in1, in2});
    }
    MatchTerm QNN_Split(MatchTerm in0, MatchTerm in1, MatchTerm in2)
    {
        return MatchTerm(next(), "q::QNN_Split", {in0, in1, in2});
    }
    MatchTerm QNN_ScatterElements(MatchTerm in0, MatchTerm in1, MatchTerm in2, MatchTerm in3, MatchTerm in4)
    {
        return MatchTerm(next(), "q::QNN_ScatterElements", {in0, in1, in2, in3, in4});
    }
    MatchTerm QNN_Gather(MatchTerm table, MatchTerm indices, MatchTerm axis)
    {
        return MatchTerm(next(), "q::QNN_Gather", {table, indices, axis});
    }
    MatchTerm QNN_GatherElements(MatchTerm table, MatchTerm indices, MatchTerm axis)
    {
        return MatchTerm(next(), "q::QNN_GatherElements", {table, indices, axis});
    }
    MatchTerm QNN_GatherNd(MatchTerm table, MatchTerm indices, MatchTerm in2, MatchTerm in3, MatchTerm in4)
    {
        return MatchTerm(next(), "q::QNN_GatherNd", {table, indices, in2, in3, in4});
    }
    MatchTerm QNN_ReduceSum(MatchTerm a, MatchTerm axes, MatchTerm keepdims)
    {
        return MatchTerm(next(), "q::QNN_ReduceSum", {a, axes, keepdims});
    }
    MatchTerm QNN_ReduceMax(MatchTerm a, MatchTerm axes, MatchTerm keepdims)
    {
        return MatchTerm(next(), "q::QNN_ReduceMax", {a, axes, keepdims});
    }
    MatchTerm QNN_Transpose(MatchTerm a, MatchTerm perm) { return MatchTerm(next(), "q::QNN_Transpose", {a, perm}); }
    MatchTerm QNN_Tile(MatchTerm a, MatchTerm multiples) { return MatchTerm(next(), "q::QNN_Tile", {a, multiples}); }
    MatchTerm QNN_ReduceMean(MatchTerm a, MatchTerm axes, MatchTerm keepdims)
    {
        return MatchTerm(next(), "q::QNN_ReduceMean", {a, axes, keepdims});
    }
    MatchTerm QNN_ElementWiseUnary(MatchTerm a, MatchTerm operation)
    {
        return MatchTerm(next(), "q::QNN_ElementWiseUnary", {a, operation});
    }
    MatchTerm QNN_ElementWiseNeuron(MatchTerm a, MatchTerm operation, MatchTerm alpha, MatchTerm beta, MatchTerm min,
                                    MatchTerm max, MatchTerm threshold)
    {
        return MatchTerm(next(), "q::QNN_ElementWiseNeuron", {a, operation, alpha, beta, min, max, threshold});
    }
    // Condition first, then a varargs run absorbing the per-expert options.
    MatchTerm QNN_ElementWiseMux(MatchTerm condition)
    {
        return MatchTerm(next(), "q::QNN_ElementWiseMux", {condition, varargs()});
    }
    // Varargs run of tensors first, axis last.
    // Note that in QNN_Concat axis is that last input not the first but
    // C++ templates makes it better to put this non-variadic term first.
    template <typename... Terms> MatchTerm QNN_Concat(MatchTerm axis, Terms... inputs)
    {
        return MatchTerm(next(), "q::QNN_Concat", {inputs..., varargs(), axis});
    }
    /** @brief Clear all bindings, readying the pattern for another root op. */
    void reset()
    {
        for (const QHPI_Op *&ref : matches_)
            ref = nullptr;
    }
    /**
     * @brief Force a binding, as RetainedMatch::restore() does.
     *
     * The assert encodes the invariant that a slot is written once per pass.
     *
     * @param t  Term whose slot to write.
     * @param op Op to bind into that slot.
     */
    void bind(MatchTerm t, const QHPI_Op *op)
    {
        assert(matches_[t.index] == nullptr);
        matches_[t.index] = op;
    }
    /**
     * @brief Match @p op against term @p t, binding slots as it recurses.
     *
     * On failure, bindings made so far are left in place — callers retry from
     * a fresh reset() rather than relying on backtracking.
     *
     * @param t  Term to match.
     * @param op Candidate op.
     * @return True if the subgraph rooted at @p op matches @p t.
     */
    bool matches(const MatchTerm &t, const QHPI_Op *op);

  private:
    /// The real matcher; matches() only wraps this with tracing.
    bool matches_impl(const MatchTerm &t, const QHPI_Op *op);

  public:
    /**
     * @brief Whether the match tracer is on.
     *
     * Off unless the QHPI_MATCH_TRACE environment variable is set, or a caller
     * assigns through the returned reference. The tracer prints one indented
     * line per term visited, so a failing pattern shows exactly which term
     * rejected which op instead of only the final false. Output goes to stderr,
     * since an op package has no access to the engine's logging macros.
     */
    static bool &trace_enabled()
    {
        static bool enabled = (getenv("QHPI_MATCH_TRACE") != nullptr);
        return enabled;
    }

    /**
     * @brief Minimum depth a trace must reach before it is printed.
     *
     * A pattern is tried against every op of the root's name, and most
     * candidates die on the first term, so tracing everything buries the one
     * attempt worth reading. Set QHPI_MATCH_TRACE to a number to print only
     * attempts that got at least that many terms deep. The whole attempt is
     * buffered and then either printed or dropped, so the surviving output is
     * still a complete tree.
     */
    static unsigned trace_min_depth()
    {
        static unsigned depth = []() {
            const char *env = getenv("QHPI_MATCH_TRACE");
            const int val = (env != nullptr) ? atoi(env) : 0;
            return unsigned(val > 0 ? val : 0);
        }();
        return depth;
    }

    /// Label prefixing traced lines, so interleaved patterns stay separable.
    const char *trace_name = "match";

    /// Collect the QHPI_OpRefs bound to the given capturing terms, in order.
    /// Each argument must be a term that owns a binding slot -- any(), an op
    /// factory, or verify() -- and output 0 is assumed. The static_assert only
    /// checks the argument type; passing a non-capturing term such as $const()
    /// indexes with NO_INDEX and is undefined.
    template <typename... Terms> std::array<QHPI_OpRef, sizeof...(Terms)> inputs(const Terms &...terms) const
    {
        static_assert(sizeof...(Terms) > 0, "Matcher::inputs: expects at least one MatchTerm");
        static_assert((std::is_same_v<std::decay_t<Terms>, MatchTerm> && ...),
                      "Matcher::inputs: every argument must be a MatchTerm");
        return std::array<QHPI_OpRef, sizeof...(Terms)>{QHPI_OpRef{matches_[terms.index], 0}...};
    }
    /** @brief Return the op bound to @p t, or null if unbound. */
    const QHPI_Op *get(const MatchTerm &t)
    {
        assert(t.index != MatchTerm::NO_INDEX);
        return matches_[t.index];
    }
};

/**
 * @brief A term whose binding survives reset().
 *
 * When a pattern repeats — as the MoE experts do — each pass needs a fresh
 * binding table, but the ops shared by every pass must stay pinned to what the
 * first pass found. Saving those terms before reset() and restoring them after
 * makes "the same router op feeds every branch" a matching constraint instead
 * of a separate check.
 */
class RetainedMatch : public MatchTerm {
  public:
    const QHPI_Op *op = nullptr;
    void save(Matcher &m) { op = m.get(*this); }
    void restore(Matcher &m) { m.bind(*this, op); }
    void operator=(const MatchTerm &t) { *(MatchTerm *)this = t; }
};

inline bool Matcher::matches(const MatchTerm &t, const QHPI_Op *op)
{
    if (not trace_enabled()) return matches_impl(t, op);

    const bool is_root = (trace_depth_ == 0);
    if (is_root) {
        trace_lines_.clear();
        trace_max_depth_ = 0;
    }

    char want[128], got[192], line[384];
    describe_term(t, want, sizeof(want));
    describe_op(op, got, sizeof(got));
    snprintf(line, sizeof(line), "%*s-> want %s | got %s", int(trace_depth_ * 2), "", want, got);
    trace_lines_.emplace_back(line);

    trace_depth_ += 1;
    if (trace_depth_ > trace_max_depth_) trace_max_depth_ = trace_depth_;
    const bool ok = matches_impl(t, op);
    trace_depth_ -= 1;

    // Only the failing line matters when reading a trace, so make it loud;
    // successes are the indented context leading up to it.
    snprintf(line, sizeof(line), "%*s%s %s", int(trace_depth_ * 2), "", ok ? "   ok" : "  FAIL", want);
    trace_lines_.emplace_back(line);

    // Print (or drop) the whole attempt once it unwinds back to the root, so a
    // shallow rejection costs nothing but a discarded buffer.
    if (is_root) {
        if (trace_max_depth_ >= trace_min_depth()) {
            fprintf(stderr, "[%s] ==== attempt on %s: %s ====\n", trace_name, got, ok ? "MATCH" : "no match");
            for (const std::string &l : trace_lines_)
                fprintf(stderr, "[%s] %s\n", trace_name, l.c_str());
        }
        trace_lines_.clear();
    }
    return ok;
}

inline bool Matcher::matches_impl(const MatchTerm &t, const QHPI_Op *op)
{
    // A term matched against one operand of an op. An ALTERNATIVE is expanded
    // here rather than in matches_impl so that each branch is tested against the
    // operand *including* its output number -- a branch may be an out() term,
    // and by the time matches() has the bare op that number is gone.
    std::function<bool(const MatchTerm &, QHPI_OpRef)> check_match = [&](const MatchTerm &t, QHPI_OpRef ref) {
        if (t.kind == MatchTerm::Kind::ALTERNATIVE) {
            // Roll back between attempts: a branch that fails after its inputs
            // matched leaves those children bound, since PATTERN binds itself
            // last. No current pattern is affected -- see alternative() -- but
            // the failure it prevents is a wrong match, not a missed one.
            const std::vector<const QHPI_Op *> saved = matches_;
            for (const MatchTerm &branch : t.inputs) {
                if (check_match(branch, ref)) return true;
                matches_ = saved;
            }
            return false;
        }
        if (t.kind == MatchTerm::Kind::OUT) {
            if (t.output_number != ref.output_number) return false;
        } else if (t.kind == MatchTerm::Kind::OUT_REF) {
            if (*t.output_number_ptr != ref.output_number) return false;
        } else {
            // Every other kind matches an op, not one of its outputs.
            if (ref.output_number != 0) return false;
        }
        return matches(t, ref.op);
    };

    // Element count of output 0 only; used to value-check whole constants.
    auto num_elements = [](const QHPI_Op *op) {
        QHPI_OutputDef out = qhpi_op_output(op, 0);
        size_t r = out.shape.rank;
        size_t total = 1;
        for (unsigned idx = 0; idx < r; idx++)
            total *= out.shape.dims[idx];
        return total;
    };

    // Read element idx of a constant as an integer, whatever its stored width.
    // A pattern says "axis 3" or "transpose 0" without caring that the graph
    // spells small integers as QUINT8/QINT8/INT64 as readily as INT32, so the
    // term language would be unusable if it demanded one exact type.
    // Quantized types are read as their raw stored integer, with no
    // (zero_offset, stepsize) applied: an integer term means "this operand is
    // the number N", which is how the graph spells enum-like parameters
    // regardless of the type it happens to store them in. Use a real-value term
    // to compare a quantized operand as a dequantized value.
    auto integer_element = [](const QHPI_Op *op, QHPI_Element_Type type, size_t idx, int64_t *out) {
        const void *data = qhpi_op_constant_data(op);
        if (data == nullptr) return false;
        switch (type) {
        case QHPI_QUINT8:
            *out = ((const uint8_t *)data)[idx];
            return true;
        case QHPI_QINT8:
            *out = ((const int8_t *)data)[idx];
            return true;
        case QHPI_QUINT16:
            *out = ((const uint16_t *)data)[idx];
            return true;
        case QHPI_QINT16:
            *out = ((const int16_t *)data)[idx];
            return true;
        case QHPI_INT32:
        case QHPI_QINT32:
            *out = ((const int32_t *)data)[idx];
            return true;
        case QHPI_INT64:
            *out = ((const int64_t *)data)[idx];
            return true;
        default:
            return false;
        }
    };

    // Same idea for floats: fp16 graphs store their scalars as FLOAT16, so a
    // float literal in a pattern has to be able to match one. The comparison is
    // done in float after widening, and the pattern's value is round-tripped
    // through fp16 so that a literal like 1.70214844 compares equal to the
    // nearest representable fp16 rather than failing on the residue.
    auto float_element = [](const QHPI_Op *op, QHPI_Element_Type type, size_t idx, float *out) {
        const void *data = qhpi_op_constant_data(op);
        if (data == nullptr) return false;
        switch (type) {
        case QHPI_FLOAT32:
            *out = ((const float *)data)[idx];
            return true;
        case QHPI_FLOAT16:
            *out = float(hexnn_fp::float16_from_raw(((const uint16_t *)data)[idx]));
            return true;
        default:
            return false;
        }
    };

    // A quantized constant carries its real value as a stored integer plus
    // (zero_offset, stepsize), so a real-value term has to compare against the
    // dequantized value to mean anything. Comparing in float would be fragile:
    // (raw - zero_offset) * stepsize almost never lands exactly on a literal
    // such as 0.0625, so an exact != would reject constants that do encode the
    // wanted value. Instead the *pattern* value is quantized to raw and the
    // stored integers are compared, which is exact and needs no epsilon. This is
    // the same trick as the fp16 round-trip above: express the pattern the way
    // the graph stores it, then compare storage to storage.
    auto quantized_element = [](const QHPI_Op *op, QHPI_Element_Type type, size_t idx, int64_t *out) {
        const void *data = qhpi_op_constant_data(op);
        if (data == nullptr) return false;
        switch (type) {
        case QHPI_QUINT8:
            *out = ((const uint8_t *)data)[idx];
            return true;
        case QHPI_QINT8:
            *out = ((const int8_t *)data)[idx];
            return true;
        case QHPI_QUINT16:
            *out = ((const uint16_t *)data)[idx];
            return true;
        case QHPI_QINT16:
            *out = ((const int16_t *)data)[idx];
            return true;
        case QHPI_QINT32:
            *out = ((const int32_t *)data)[idx];
            return true;
        default:
            return false;
        }
    };

    // True for the element types quantized_element can read, i.e. those whose
    // stored integer needs (zero_offset, stepsize) applied to recover a value.
    auto is_quantized_type = [](QHPI_Element_Type type) {
        return type == QHPI_QUINT8 or type == QHPI_QINT8 or type == QHPI_QUINT16 or type == QHPI_QINT16 or
               type == QHPI_QINT32;
    };

    switch (t.kind) {
    case MatchTerm::Kind::CONST:
        if (not qhpi_op_is_constant(op)) return false;
        return true;
    // A value-checked constant must match in *every* element, so a scalar
    // literal in a pattern also matches a splatted tensor of that value.
    case MatchTerm::Kind::INTEGER:
    // INT_REF is INTEGER with the wanted value read at match time rather than
    // fixed when the pattern was built, so one term can walk a different
    // per-expert constant on each pass.
    case MatchTerm::Kind::INT_REF: {
        if (not qhpi_op_is_constant(op)) return false;
        const int64_t want =
                (t.kind == MatchTerm::Kind::INT_REF) ? int64_t(t.int_ptr ? *t.int_ptr : 0u) : int64_t(t.ival);
        const QHPI_Element_Type type = qhpi_op_output(op, 0).type;
        size_t total = num_elements(op);
        for (unsigned idx = 0; idx < total; idx++) {
            int64_t val = 0;
            if (not integer_element(op, type, idx, &val)) return false;
            if (val != want) return false;
        }
        return true;
    }
    case MatchTerm::Kind::REAL: {
        if (not qhpi_op_is_constant(op)) return false;
        const QHPI_OutputDef out = qhpi_op_output(op, 0);
        const QHPI_Element_Type type = out.type;
        size_t total = num_elements(op);
        // A quantized constant is compared in its stored integer domain: the
        // pattern value is quantized once, up front, and matched against the raw
        // elements. Two values in the same quantization bucket compare equal,
        // which is the intended reading -- the constant cannot distinguish them.
        // A literal outside the storage range quantizes to a raw value no element
        // can hold, so it simply fails to match.
        if (is_quantized_type(type)) {
            const QHPI_Quant_Parameters q = out.quant_parameters;
            // Guard the divide: a zero or non-finite stepsize means the params
            // are unusable rather than that everything matches. A FLT_MAX
            // stepsize (the QHPI_Quant_Parameters_Invalid sentinel) is finite and
            // passes here, but then quantizes any literal to a raw 0 or
            // zero_offset, so it will not spuriously match a real constant.
            if (not std::isfinite(q.stepsize) or q.stepsize == 0.0f) return false;
            const float scaled = std::round(t.fval / q.stepsize + float(q.zero_offset));
            if (not std::isfinite(scaled)) return false;
            constexpr double INT64_MIN_VALUE = double(std::numeric_limits<int64_t>::min());
            constexpr double INT64_MAX_EXCLUSIVE = -INT64_MIN_VALUE;
            if (double(scaled) < INT64_MIN_VALUE or double(scaled) >= INT64_MAX_EXCLUSIVE) return false;
            const int64_t want = int64_t(scaled);
            for (unsigned idx = 0; idx < total; idx++) {
                int64_t val = 0;
                if (not quantized_element(op, type, idx, &val)) return false;
                if (val != want) return false;
            }
            return true;
        }
        // Compare against the pattern value as the graph would store it, so an
        // fp16 constant is not rejected for the precision it cannot carry.
        const float want = (type == QHPI_FLOAT16) ? float(Float16(t.fval)) : t.fval;
        for (unsigned idx = 0; idx < total; idx++) {
            float val = 0;
            if (not float_element(op, type, idx, &val)) return false;
            if (val != want) return false;
        }
        return true;
    }
    // Bind-or-compare: re-matching the same op succeeds, a conflicting op
    // fails. This is what makes one term used twice in a pattern mean "the
    // same op both times" rather than "two ops of this shape".
    //
    // Do not relax the conflict arm into clearing the slot and carrying on. It
    // is load-bearing: MoEBranch shares one tokens term between the router Conv
    // and every expert MatMul, and that shared slot is the only thing requiring
    // an expert to read the tokens its router actually scored. Without the check
    // a graph whose expert reads a different tensor matches anyway. The sweep in
    // docs/moe does not catch this -- removing the check only ever makes matching
    // more permissive, so the models keep matching and say nothing.
    case MatchTerm::Kind::ANY: {
        if (matches_[t.index] == op) return true;
        if (matches_[t.index] != nullptr) return false;
        matches_[t.index] = op;
        return true;
    }
    case MatchTerm::Kind::PATTERN: {
        if (matches_[t.index] == op) return true;
        if (matches_[t.index] != nullptr) return false;

        if (strcmp(qhpi_op_name(op), t.name)) return false;
        unsigned N = t.inputs.size();
        unsigned input_number = 0;
        unsigned actual = qhpi_op_num_inputs(op);

        // A VARARGS term absorbs however many inputs the op has beyond the other
        // N-1 terms, so it consumes (actual - N) + 1 input positions. The final
        // input_number == actual check then rejects any op whose arity the terms
        // do not account for exactly. With actual=9:
        //   mux(cond, varargs)          N=2, varargs at idx 1 -> 1 + 8
        //   concat(first, varargs, ax)  N=3, varargs at idx 1 -> 1 + 7 + 1
        //   concat(varargs, axis)       N=2, varargs at idx 0 -> 8 + 1

        for (unsigned idx = 0; idx < N; idx++) {
            const MatchTerm &term = t.inputs[idx];
            if (term.kind == MatchTerm::Kind::VARARGS) {
                if (actual < N) return false;
                input_number += (actual - N) + 1;
                continue;
            }
            if (input_number >= actual) return false;
            QHPI_OpRef input = qhpi_op_input(op, input_number);
            if (not check_match(t.inputs[idx], input)) return false;
            input_number += 1;
        }
        if (input_number != actual) return false;
        matches_[t.index] = op;
        return true;
    }
    // Only meaningful as a marker inside a PATTERN's input list, which is
    // handled above; reaching it directly is a malformed pattern.
    case MatchTerm::Kind::VARARGS:
        return false;
    case MatchTerm::Kind::OUT: {
        unsigned N = qhpi_op_num_outputs(op);
        if (N <= t.output_number) return false;
        if (not matches(t.inputs[0], op)) return false;
        return true;
    }
    case MatchTerm::Kind::OUT_REF: {
        unsigned N = qhpi_op_num_outputs(op);
        if (N <= *t.output_number_ptr) return false;
        if (not matches(t.inputs[0], op)) return false;
        return true;
    }
    case MatchTerm::Kind::VERIFY:
        if (not matches(t.inputs[0], op)) return false;
        if (not t.predicate(op)) return false;
        assert(t.index == t.inputs[0].index);
        return true;
    // Reached when an ALTERNATIVE is the whole term rather than one operand of an
    // op; check_match handles the operand case, where the output number matters.
    case MatchTerm::Kind::ALTERNATIVE: {
        // Rolled back per attempt for the reason given in alternative().
        const std::vector<const QHPI_Op *> saved = matches_;
        for (const MatchTerm &branch : t.inputs) {
            if (matches(branch, op)) return true;
            matches_ = saved;
        }
        return false;
    }
    default:
        assert(false && "missing case in matches()");
        return false;
    }
}
} // namespace qhpi
