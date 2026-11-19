package com.personal.bookmark_app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.UUID

internal const val BOOKMARK_SHARE_ID = "com.personal.bookmark_app.extra.BOOKMARK_SHARE_ID"

class ShareBookmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND &&
            intent.type?.startsWith("text/") == true
        ) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    .orEmpty()
            // Open the app's launcher task so Android can return to it independently of the sender.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(BOOKMARK_SHARE_ID, UUID.randomUUID().toString())
                    .putExtra(Intent.EXTRA_TEXT, text.take(20000))
                    .putExtra(Intent.EXTRA_SUBJECT, intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().take(255))
            )
        }
        finish()
    }
}
