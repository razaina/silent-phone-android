/*
Copyright (C) 2016, Silent Circle, LLC.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Any redistribution, use, or modification is done solely for personal
      benefit and not for any commercial purpose or for monetary gain
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name Silent Circle nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL SILENT CIRCLE, LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package com.silentcircle.messaging.task;

import android.content.Context;
import android.os.AsyncTask;

import com.silentcircle.messaging.model.Conversation;
import com.silentcircle.messaging.model.MessageStates;
import com.silentcircle.messaging.model.event.Message;
import com.silentcircle.messaging.repository.ConversationRepository;
import com.silentcircle.messaging.services.AxoMessaging;
import com.silentcircle.messaging.util.MessageUtils;
import com.silentcircle.messaging.util.SoundNotifications;

import axolotl.AxolotlNative;

/**
 * Async task to send a composed message.
 */
public class SendMessageTask extends AsyncTask<Message, Void, Message> {

    private final Context mContext;

    private boolean mResultStatus = true;
    private int mResultCode = 0;
    private String mResultInfo = null;
    private boolean mSiblingsOnly;

    public SendMessageTask(Context context) {
        mContext = context;
    }

    public SendMessageTask(Context context, boolean siblingsOnly) {
        mContext = context;
        mSiblingsOnly = siblingsOnly;
    }

    @Override
    protected Message doInBackground(Message... params) {
        Message message = params[0];

        if (message != null) {
            AxoMessaging msgService = AxoMessaging.getInstance(mContext);
            mResultStatus = msgService.sendMessage(message, mSiblingsOnly);

            ConversationRepository repository = msgService.getConversations();
            Conversation conversation =
                    msgService.getOrCreateConversation(message.getConversationID());

            if (!mResultStatus) {
                message.setState(MessageStates.FAILED);
                mResultCode = AxolotlNative.getErrorCode();
                mResultInfo = AxolotlNative.getErrorInfo();
                // Save message with new state here
                repository.historyOf(conversation).save(message);
            } else {
                message.setState(MessageStates.SENT);

                // update and persist conversation modification time
                conversation.setLastModified(System.currentTimeMillis());
                repository.save(conversation);

                if (!mSiblingsOnly) {
                    SoundNotifications.playSentMessageSound();
                }
            }

            if (!mSiblingsOnly) {
                // notify about conversation changes
                MessageUtils.notifyConversationUpdated(mContext, message.getConversationID(), false,
                        AxoMessaging.UPDATE_ACTION_MESSAGE_SEND, message.getId());
            }
        }

        return message;
    }

    public boolean getResultStatus() {
        return mResultStatus;
    }

    public int getResultCode() {
        return mResultCode;
    }

    public String getResultInfo() {
        return mResultInfo;
    }
}
