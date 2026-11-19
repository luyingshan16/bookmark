package com.personal.bookmark_app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.personal.bookmark_app.navigation.AppNavigation
import com.personal.bookmark_app.ui.theme.BookmarkappTheme
import dagger.hilt.android.AndroidEntryPoint

data class SharedBookmark(val id: String, val text: String, val title: String)

private const val HANDLED_SHARE_IDS = "handled_share_ids"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var sharedBookmark by mutableStateOf<SharedBookmark?>(null)
    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedBookmark = savedInstanceState?.getString("shareId")?.let {
            SharedBookmark(
                it,
                savedInstanceState.getString("shareText").orEmpty(),
                savedInstanceState.getString("shareTitle").orEmpty()
            )
        }?.takeUnless { wasShareHandled(it.id) }
        if (savedInstanceState == null) receiveShare(intent)
        enableEdgeToEdge()
        setContent {
            BookmarkappTheme {
                AppNavigation(sharedBookmark = sharedBookmark, onShareHandled = { id ->
                    markShareHandled(id)
                })
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val preferences = getPreferences(MODE_PRIVATE)
        val permissionHandled = "notification_permission_handled"
        if (preferences.getBoolean(permissionHandled, false)) return

        preferences.edit().putBoolean(permissionHandled, true).apply()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sharedBookmark?.let {
            outState.putString("shareId", it.id)
            outState.putString("shareText", it.text)
            outState.putString("shareTitle", it.title)
        }
        super.onSaveInstanceState(outState)
    }

    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_MAIN) return
        val id = intent.getStringExtra(BOOKMARK_SHARE_ID) ?: return
        if (sharedBookmark?.id == id || wasShareHandled(id)) return
        sharedBookmark = SharedBookmark(
            id,
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(20000),
            intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().take(255)
        )
    }

    private fun wasShareHandled(id: String): Boolean =
        id in getPreferences(MODE_PRIVATE).getStringSet(HANDLED_SHARE_IDS, emptySet()).orEmpty()

    private fun markShareHandled(id: String) {
        val preferences = getPreferences(MODE_PRIVATE)
        val handledIds = preferences.getStringSet(HANDLED_SHARE_IDS, emptySet()).orEmpty()
        preferences.edit().putStringSet(HANDLED_SHARE_IDS, handledIds + id).apply()
        if (sharedBookmark?.id == id) sharedBookmark = null
    }
}
