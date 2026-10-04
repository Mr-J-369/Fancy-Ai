#include <jni.h>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <vector>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void fail(JNIEnv *env) {
    env->ThrowNew(env->FindClass("java/io/IOException"), strerror(errno));
}

static std::vector<std::string> strings(JNIEnv *env, jobjectArray values) {
    std::vector<std::string> result;
    for (jsize i = 0; i < env->GetArrayLength(values); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i)); // NOLINT(cppcoreguidelines-pro-type-static-cast-downcast): JNI String[] uses opaque object handles.
        const char *text = env->GetStringUTFChars(value, nullptr);
        result.emplace_back(text);
        env->ReleaseStringUTFChars(value, text);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mrj_fancyai_terminal_NativePty_start(JNIEnv *env, jobject, jobjectArray args, jobjectArray environment) {
    auto argv_strings = strings(env, args);
    auto env_strings = strings(env, environment);
    std::vector<char *> argv, envp;
    for (auto &arg : argv_strings) argv.push_back(arg.data());
    for (auto &arg : env_strings) envp.push_back(arg.data());
    argv.push_back(nullptr);
    envp.push_back(nullptr);
    int master = posix_openpt(O_RDWR | O_CLOEXEC);
    if (master < 0) { fail(env); return -1; }
    char slave_name[128];
    if (grantpt(master) || unlockpt(master) || ptsname_r(master, slave_name, sizeof(slave_name))) {
        int saved = errno; close(master); errno = saved; fail(env); return -1;
    }
    const pid_t parent = getpid();
    const pid_t child = fork();
    if (child < 0) { int saved = errno; close(master); errno = saved; fail(env); return -1; }
    if (child == 0) {
        // Only async-signal-safe operations after forking the Android VM.
        prctl(PR_SET_PDEATHSIG, SIGQUIT);
        if (getppid() != parent) _exit(127);
        prctl(PR_SET_DUMPABLE, 1); // PRoot must trace its own children in release builds too.
        if (setsid() < 0) _exit(127);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(127);
        winsize size{24, 80, 0, 0};
        ioctl(slave, TIOCSWINSZ, &size);
        for (int fd = 0; fd < 3; ++fd) if (dup2(slave, fd) < 0) _exit(127);
        if (slave > 2) close(slave);
        close(master);
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, nullptr);
        struct sigaction action{};
        action.sa_handler = SIG_DFL;
        for (int signal_number : {SIGINT, SIGTERM, SIGHUP, SIGQUIT, SIGPIPE, SIGCHLD}) {
            sigaction(signal_number, &action, nullptr);
        }
        execve(argv[0], argv.data(), envp.data());
        _exit(127);
    }
    return (static_cast<jlong>(child) << 32) | static_cast<unsigned int>(master);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_terminal_NativePty_resize(JNIEnv *env, jobject, jint fd, jint columns, jint rows) {
    winsize size{static_cast<unsigned short>(rows), static_cast<unsigned short>(columns), 0, 0};
    if (ioctl(fd, TIOCSWINSZ, &size) < 0) fail(env);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mrj_fancyai_terminal_NativePty_waitFor(JNIEnv *env, jobject, jint pid) {
    siginfo_t status{};
    int result;
    // Retain the zombie until Kotlin marks the session ended, preventing PID reuse during stop().
    do { result = waitid(P_PID, pid, &status, WEXITED | WNOWAIT); } while (result < 0 && errno == EINTR);
    if (result < 0) { fail(env); return -1; }
    return status.si_code == CLD_EXITED ? status.si_status : 128 + status.si_status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_terminal_NativePty_reap(JNIEnv *, jobject, jint pid) {
    while (waitpid(pid, nullptr, 0) < 0 && errno == EINTR) {}
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrj_fancyai_terminal_NativePty_stop(JNIEnv *env, jobject, jint pid) {
    // PRoot ignores SIGTERM; SIGQUIT kills its tracees and exits through its event loop.
    if (kill(pid, SIGQUIT) < 0 && errno != ESRCH) fail(env);
}
