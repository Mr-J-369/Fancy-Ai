package com.mrj.fancyai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal fun ChatController.replaceConversation(conversation: ChatConversation) {
    conversations = conversations.map { if (it.id == conversation.id) conversation else it }
}

internal fun ChatController.selectConversation(conversation: ChatConversation) {
    if (loading || generating || rebuilding) return
    stopListening()
    stopSpeaking()
    speechError = 0
    activeChatId = conversation.id
    input = readChatDraft(context, character.id, conversation.id)
    attachedImagePath = readAttachmentDraft(context, character.id, conversation.id)
    attachmentError = 0

    showConversations = false
}

internal fun ChatController.newConversation() {
    if (loading || generating || rebuilding) return
    val conversation = ChatConversation(id = UUID.randomUUID().toString())
    conversations = listOf(conversation) + conversations
    scope.launch {
        persistNow(conversation.id)
    }
    selectConversation(conversation)
}

internal fun ChatController.renameConversation(id: String, title: String) {
    if (loading || generating || rebuilding) return
    val conversation = conversations.firstOrNull { it.id == id } ?: return
    replaceConversation(conversation.copy(title = title.trim()))
    rebuilding = true
    scope.launch {
        try {
            persistNow(id)
            saveRenameDraft(context, id, "")
        } finally {
            rebuilding = false
        }
    }
}

internal fun ChatController.deleteConversation(id: String) {
    if (loading || generating || rebuilding) return
    stopListening()
    stopSpeaking()
    rebuilding = true
    deletingConversations += id
    scope.launch {
        try {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    chatPersistenceLock.withLock { removeConversation(context, character.id, id) }
                }
                memory.deleteSession(id)
                savedConversations.remove(id)
                removeChatDraft(context, character.id, id)
                saveRenameDraft(context, id, "")
                conversations = conversations.filterNot { it.id == id }
                if (conversations.isEmpty()) {
                    conversations = listOf(ChatConversation(id = UUID.randomUUID().toString()))
                }
                rebuilding = false
                if (activeChatId == id) selectConversation(conversations.maxBy(ChatConversation::updatedAt))
                rebuilding = true
                persistNow(activeChatId)
            }
        } finally {
            deletingConversations -= id
            rebuilding = false
        }
    }
}

internal fun ChatController.retryEngine() {
    if (loading || rebuilding || generating) return
    stopListening()
    stopSpeaking()
    loading = true
    loadJob?.cancel()
    loadJob = scope.launch { load() }
}

internal fun ChatController.send() {
    val conversation = activeConversation ?: return
    if (generating || rebuilding || importingImage) return
    val message = input.trim().capitalizeFirstVisibleLetter()
    val image = attachedImagePath
    if (message.isEmpty() && image == null) return
    startReply(conversation, message, imagePath = image, rebuild = false)
}

internal fun ChatController.copyMessage(message: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.chat_message_copy), message),
    )
}

internal fun ChatController.regenerateReply(index: Int) {
    val conversation = activeConversation ?: return
    val turn = conversation.turns.getOrNull(index) ?: return
    if ((index != conversation.turns.lastIndex) || !modelAvailable || loading || generating || rebuilding) return
    val prefix = conversation.copy(
        updatedAt = System.currentTimeMillis(),
        turns = conversation.turns.take(index),
    )
    startReply(prefix, turn.user, turn.userImagePath, turn.userImageDescription, turn.modelInput, rebuild = true)
}

internal fun ChatController.editUserMessage(index: Int, replacement: String) {
    val conversation = activeConversation ?: return
    val message = replacement.trim().capitalizeFirstVisibleLetter()
    val invalidTurn = index !in conversation.turns.indices
    if (invalidTurn || !modelAvailable || loading || generating || rebuilding) return
    if (message.isEmpty() && (conversation.turns[index].userImagePath == null)) return
    val prefix = conversation.copy(
        updatedAt = System.currentTimeMillis(),
        turns = conversation.turns.take(index),
    )
    val turn = conversation.turns[index]
    startReply(prefix, message, turn.userImagePath, turn.userImageDescription, rebuild = true, editedUser = true)
}

internal fun ChatController.editReply(index: Int, replacement: String) {
    val conversation = activeConversation ?: return
    val reply = replacement.trim().capitalizeFirstVisibleLetter()
    val invalidTurn = index !in conversation.turns.indices
    if (reply.isEmpty() || loading || generating || rebuilding || invalidTurn) return
    stopSpeaking()
    val updated = conversation.copy(
        updatedAt = System.currentTimeMillis(),
        turns = conversation.turns.mapIndexed { turnIndex, turn ->
            if (turnIndex == index) turn.withReplyText(reply) else turn
        },
    )
    replaceConversation(updated)
    rebuilding = true
    scope.launch {
        try {
            persistNow(updated.id)
            saveMessageEditDraft(
                context,
                character.id,
                updated.id,
                index,
                user = false,
                draft = "",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logLlmError(failure, R.string.chat_engine_failed)
        } finally {
            rebuilding = false
        }
    }
}

internal fun ChatController.deleteFrom(index: Int) {
    val conversation = activeConversation ?: return
    val invalidTurn = index !in conversation.turns.indices
    if (loading || generating || rebuilding || invalidTurn) return
    stopSpeaking()
    val remaining = conversation.turns.take(index)
    val updated = conversation.copy(
        title = conversation.title.takeIf { remaining.isNotEmpty() }.orEmpty(),
        updatedAt = System.currentTimeMillis(),
        turns = remaining,
    )
    replaceConversation(updated)
    rebuilding = true
    scope.launch {
        try {
            persistNow(updated.id)
            memory.deleteSessionFrom(updated.id, index)
            for (removedIndex in index until conversation.turns.size) {
                saveMessageEditDraft(context, character.id, updated.id, removedIndex, user = true, draft = "")
                saveMessageEditDraft(context, character.id, updated.id, removedIndex, user = false, draft = "")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logLlmError(failure, R.string.chat_engine_failed)
        } finally {
            rebuilding = false
        }
    }
}

internal fun ChatController.importAttachment(uri: Uri, target: String) {
    scope.launch {
        importingImage = true
        attachmentError = 0
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val path = saveChatAttachment(context, uri)
                chatPersistenceLock.withLock {
                    if (conversationFile(context, character.id, target).isFile) {
                        saveAttachmentDraft(context, character.id, target, path)
                    } else deleteChatAttachment(context, path)
                }
            }
            if (activeChatId == target) {
                attachedImagePath = readAttachmentDraft(context, character.id, target)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (activeChatId == target) attachmentError = R.string.chat_image_import_failed
        } finally {
            importingImage = false
        }
    }
}

internal fun ChatController.removeAttachment() {
    if (generating || rebuilding || importingImage) return
    val chatId = activeChatId
    attachedImagePath = null
    scope.launch {
        withContext(Dispatchers.IO) {
            chatPersistenceLock.withLock { saveAttachmentDraft(context, character.id, chatId, null) }
        }
    }
}
