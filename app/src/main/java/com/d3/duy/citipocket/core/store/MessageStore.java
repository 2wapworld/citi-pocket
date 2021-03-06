package com.d3.duy.citipocket.core.store;

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.Telephony;
import android.util.Log;

import com.d3.duy.citipocket.core.enrich.MessageEnrichment;
import com.d3.duy.citipocket.core.enrich.TransactionDuplicateRemover;
import com.d3.duy.citipocket.model.MessageContract;
import com.d3.duy.citipocket.model.MessageEnrichmentHolder;
import com.d3.duy.citipocket.model.MessageHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by daoducduy0511 on 3/4/16.
 */
public class MessageStore {
    private static final String TAG = MessageStore.class.getSimpleName();

    public static final int BATCH_SIZE = 50;
    public static final int BATCH_WINDOW_SIZE = 2;
    public static final int TOTAL_ITEM_IN_WINDOW_SIZE = BATCH_WINDOW_SIZE * BATCH_SIZE;

    private static MessageStore ourInstance = null;
    private static ContentResolver ourContentResolver = null;

    private List<MessageHolder> messageHolderList = new ArrayList<>();
    private List<MessageEnrichmentHolder> enrichedMessageHolderList = new ArrayList<>();
    private boolean isLoaded = false;
    private int currentWindow = 1;
    private int currentFromPosition = 0;
    private int currentToPosition = 0;
    private int total = 0;

    private MessageStore() { }
    public static MessageStore getInstance( ContentResolver contentResolver) {
        ourContentResolver = contentResolver;
        if (ourInstance == null) ourInstance = new MessageStore();
        return ourInstance;
    }

    public static MessageStore getInstance() {
        return ourInstance;
    }

    public List<MessageHolder> getMessages() {
        return messageHolderList;
    }

    public List<MessageEnrichmentHolder> getEnrichedMessages() {
        return enrichedMessageHolderList;
    }

    public boolean isMorePrevious() {
        if (currentWindow == 1 &&
                (currentFromPosition == 0 || currentToPosition <= TOTAL_ITEM_IN_WINDOW_SIZE))
            return false;
        else return true;
    }

    public boolean isMoreNext() {
        if (currentWindow == BATCH_WINDOW_SIZE &&
                (currentToPosition > total || currentFromPosition >= total - TOTAL_ITEM_IN_WINDOW_SIZE))
            return false;
        else return true;
    }

    public void moveToPreviousBatch() {
        Log.d(TAG, String.format("Moving to previous batch: Before: window %d, from %d, to %d",
                currentWindow, currentFromPosition, currentToPosition));
        if (!isMorePrevious()) return;

        if (currentWindow > 1) currentWindow -= 1;

        currentToPosition -= BATCH_SIZE;
        if (currentToPosition <= TOTAL_ITEM_IN_WINDOW_SIZE) {
            // if this is the first batch
            currentWindow = 1;
            if (total > TOTAL_ITEM_IN_WINDOW_SIZE) {
                currentToPosition = TOTAL_ITEM_IN_WINDOW_SIZE;
            } else {
                currentToPosition = total;
            }
            currentFromPosition = 0;
        } else {
            currentFromPosition -= BATCH_SIZE;
            if (currentFromPosition < 0) {
                currentFromPosition = 0;
            }
        }

        Log.d(TAG, String.format("Moving to previous batch: After: window %d, from %d, to %d",
                currentWindow, currentFromPosition, currentToPosition));
    }

    public void moveToNextBatch() {
        Log.d(TAG, String.format("Moving to next batch: Before: window %d, from %d, to %d",
                currentWindow, currentFromPosition, currentToPosition));

        if (!isMoreNext()) return;

        // check if this is the first batch
        if (currentWindow == 1 && currentToPosition == 0) {
            if (total <= TOTAL_ITEM_IN_WINDOW_SIZE) currentToPosition = total;
            else currentToPosition = TOTAL_ITEM_IN_WINDOW_SIZE;
        } else {

            // check if this is last batch
            if (currentToPosition + BATCH_SIZE > total) {
                if (currentToPosition < total) {
                    int delta = total - currentToPosition;
                    currentToPosition = total;
                    currentFromPosition += delta;
                    currentWindow = BATCH_WINDOW_SIZE;
                }
            } else {
                currentWindow += 1;
                currentFromPosition += BATCH_SIZE;
                currentToPosition += BATCH_SIZE;
                if (currentToPosition > total) {
                    currentToPosition = total;
                }
            }
        }

        Log.d(TAG, String.format("Moving to next batch: After: window %d, from %d, to %d",
                currentWindow, currentFromPosition, currentToPosition));
    }

    public List<MessageHolder> getMessagesInBatch() {
         return getMessages().subList(currentFromPosition, currentToPosition);
    }

    public List<MessageEnrichmentHolder> getEnrichedMessagesInBatch() {
        return getEnrichedMessages().subList(currentFromPosition, currentToPosition);
    }

    public int getMessagesSize() {
        return total;
    }

    public boolean isLoaded() {
        return isLoaded;
    }

    public void load(boolean forceReload) {
//        synchronized (this) {
            // prevent reload many times
            if (!forceReload && isLoaded) return;

            List<MessageHolder> messageHolders = new ArrayList<>();
            /**
             *  // Example: Queries the user dictionary and returns results
             *  mCursor = getContentResolver().query(
             *        UserDictionary.Words.CONTENT_URI,   // The content URI of the words table, "FROM table_name"
             *        mProjection,                        // The columns to return for each row, "col,col,col,..."
             *        mSelectionClause                    // Selection criteria, "WHERE col = value"
             *        mSelectionArgs,                     // Selection criteria
             *        mSortOrder);                        // The sort order for the returned rows, "ORDER BY col,col,...
             */
            String[] mSelectionArgs = {""};
            mSelectionArgs[0] = MessageContract.SENDER_ADDRESS;
            Cursor c = ourContentResolver.query(
                    MessageContract.URI,
                    MessageContract.COLUMNS,
                    MessageContract.SELECTION_SENDER_ADDRESS,
                    mSelectionArgs,
                    null);

            int count = c.getCount();
            Log.d(TAG, "Querying to database, found " + count + " messages");

            // Read the sms data and store it in the list
            if (c.moveToFirst()) {
                for (int i = 0; i < c.getCount(); i++) {
                    int id = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms._ID));
                    String number = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).toString();
                    String body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)).toString();
                    String date = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.DATE)).toString();

                    messageHolders.add(new MessageHolder(id, number, body, date));
                    c.moveToNext();
                }
            }
            c.close();

            messageHolderList.clear();
            messageHolderList.addAll(messageHolders);
            isLoaded = true;
            total = messageHolderList.size();

            // transform all normal messages to enriched type
            for (MessageHolder message: messageHolderList) {
                MessageEnrichmentHolder enrichedMessage = MessageEnrichment.classify(message);

                if (TransactionDuplicateRemover.isDuplicate(enrichedMessage) ||
                        TransactionDuplicateRemover.isRedundant(enrichedMessage)) continue;

                else enrichedMessageHolderList.add(enrichedMessage);
            }
//        }
    }

}
