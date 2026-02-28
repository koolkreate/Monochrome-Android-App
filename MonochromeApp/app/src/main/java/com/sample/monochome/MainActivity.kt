package com.sample.monochome

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    // Child WebView used to handle Google OAuth popups (signInWithPopup)
    private var popupWebView: WebView? = null

    private fun closePopupWebView() {
        (popupWebView?.parent as? ViewGroup)?.removeView(popupWebView)
        popupWebView?.destroy()
        popupWebView = null
    }

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaPlaybackService.ACTION_PLAY_PAUSE -> {
                    // Automatically find "Play" or "Pause" button on the webpage
                    webView.evaluateJavascript("""
                        var playPauseBtn = document.querySelector('.buttons .play-pause-btn');
                        if (playPauseBtn) playPauseBtn.click();
                    """.trimIndent(), null)
                }
                MediaPlaybackService.ACTION_NEXT -> {
                    // Automatically find "Next" button on the webpage
                    webView.evaluateJavascript("""
                        var nextBtn = document.querySelector('#next-btn');
                        if (nextBtn) nextBtn.click();
                    """.trimIndent(), null)
                }
                MediaPlaybackService.ACTION_PREV -> {
                    // Automatically find "Previous" button on the webpage
                    webView.evaluateJavascript("""
                        var prevBtn = document.querySelector('#prev-btn');
                        if (prevBtn) prevBtn.click();
                    """.trimIndent(), null)
                }
                MediaPlaybackService.ACTION_LIKE -> {
                    // Click the Like button
                    webView.evaluateJavascript("""
                        var likeBtn = document.querySelector('#now-playing-like-btn');
                        if (likeBtn) likeBtn.click();
                    """.trimIndent(), null)
                }
                MediaPlaybackService.ACTION_SEEK_TO -> {
                    val pos = intent.getLongExtra("position", 0L) / 1000.0
                    // Seek the audio element
                    webView.evaluateJavascript("""
                        var audio = document.querySelector('audio');
                        if (audio) audio.currentTime = $pos;
                    """.trimIndent(), null)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        
        // Start Foreground Service
        val serviceIntent = Intent(this, MediaPlaybackService::class.java)
        serviceIntent.action = "START_FOREGROUND"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Register Receiver for Media Controls
        val filter = IntentFilter().apply {
            addAction(MediaPlaybackService.ACTION_PLAY_PAUSE)
            addAction(MediaPlaybackService.ACTION_NEXT)
            addAction(MediaPlaybackService.ACTION_PREV)
            addAction(MediaPlaybackService.ACTION_LIKE)
            addAction(MediaPlaybackService.ACTION_SEEK_TO)
        }
        ContextCompat.registerReceiver(this, mediaControlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // WebSettings Optimization
        val webSettings: WebSettings = webView.settings
        
        // 1. Enable JavaScript
        webSettings.javaScriptEnabled = true
        
        // Add Javascript Interface
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // 2. Enable DOM Storage
        webSettings.domStorageEnabled = true
        
        // 3. Set Cache Mode (LOAD_DEFAULT uses cache when available, otherwise network)
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        
        // Enable Third-Party Cookies for Firebase Authentication
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        
        // Enable database/localStorage for Firebase Auth state persistence
        webSettings.databaseEnabled = true
        webSettings.domStorageEnabled = true
        
        // Optional: Additional performance and UX enhancements
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        
        // Spoof User Agent completely to a standard Chrome browser to bypass Google's strict OAuth WebView block
        val chromeUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
        webSettings.userAgentString = chromeUserAgent

        // Enable popup windows so Firebase signInWithPopup works
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.setSupportMultipleWindows(true)

        // 4. Attach a custom WebChromeClient that handles Google OAuth popup windows
        webView.webChromeClient = object : WebChromeClient() {
            @SuppressLint("SetJavaScriptEnabled")
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                // Create a child WebView to host the OAuth popup (e.g. Google sign-in page)
                val childWebView = WebView(this@MainActivity)
                childWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = webSettings.userAgentString
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(childWebView, true)

                childWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        // OAuth popup is done — remove the overlay
                        closePopupWebView()
                    }
                }

                childWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return false // Let all URLs load inside the popup
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Firebase's OAuth handler page is where the auth result lands.
                        // Due to COOP headers, the popup cannot call window.opener.postMessage()
                        // or window.close(). So we manually:
                        // 1. Extract the auth credential from the handler page
                        // 2. Relay it to the parent window
                        // 3. Close the popup
                        if (url != null && url.contains("/__/auth/handler")) {
                            view?.evaluateJavascript("""
                                (function() {
                                    // Wait a moment for Firebase JS to process the auth response
                                    setTimeout(function() {
                                        // Close popup and let Firebase on the main page detect the auth state change
                                        window.close();
                                    }, 2000);
                                })();
                            """.trimIndent(), null)

                            // Also schedule cleanup from Android side in case window.close doesn't fire
                            view?.postDelayed({
                                closePopupWebView()
                                // Reload the main page to pick up the new auth state from Firebase
                                webView.evaluateJavascript("""
                                    if (typeof location !== 'undefined') location.reload();
                                """.trimIndent(), null)
                            }, 3000)
                        }
                    }
                }

                // Overlay the popup WebView on top of the main WebView inside the same container
                val container = webView.parent as ViewGroup
                container.addView(childWebView, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                popupWebView = childWebView

                // Connect the new window transport so the browser engine fills the child WebView
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = childWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                closePopupWebView()
            }
        }

        // 5. Navigation Handling: Ensure links open inside the WebView,
        // but handle secure flows like Google OAuth externally.
        webView.webViewClient = object : WebViewClient() {
            
            // For newer API levels
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return handleUrlLoading(view, url)
            }

            // For older API levels
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrlLoading(view, url)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject Javascript to continuously read MediaSession metadata
                view?.evaluateJavascript("""
                    setInterval(function() {
                        var title = '';
                        var artist = '';
                        var coverUrl = '';
                        var duration = 0;
                        var position = 0;
                        
                        // Parse MediaSession API
                        if (navigator.mediaSession && navigator.mediaSession.metadata) {
                            title = navigator.mediaSession.metadata.title || '';
                            artist = navigator.mediaSession.metadata.artist || '';
                        }
                        
                        // Extract cover art from DOM directly (more reliable)
                        var coverImg = document.querySelector('.now-playing-bar .track-info img.cover');
                        if (coverImg && coverImg.src) {
                            coverUrl = coverImg.src;
                        }
                        
                        // Check if audio is playing and get timing
                        var isPlaying = false;
                        var audio = document.querySelector('audio');
                        if (audio) {
                            if (!audio.paused) isPlaying = true;
                            if (audio.duration) duration = Math.floor(audio.duration * 1000);
                            if (audio.currentTime) position = Math.floor(audio.currentTime * 1000);
                        } else {
                            var mediaElements = document.querySelectorAll('audio, video');
                            for (var i = 0; i < mediaElements.length; i++) {
                                if (!mediaElements[i].paused) {
                                    isPlaying = true;
                                    break;
                                }
                            }
                        }
                        
                        // Fallback title from DOM if MediaSession is not initialized yet
                        if (!title && isPlaying) {
                            var docTitle = document.title || 'Playing Audio';
                            if (docTitle.indexOf(' • ') !== -1) {
                                var parts = docTitle.split(' • ');
                                title = parts[0].trim();
                                artist = parts.slice(1).join(' • ').trim();
                            } else if (docTitle.indexOf(' - ') !== -1) {
                                var parts = docTitle.split(' - ');
                                title = parts[0].trim();
                                artist = parts.slice(1).join(' - ').trim();
                            } else {
                                title = docTitle;
                            }
                        }
                        
                        // Check if the current song is liked by checking the active class on the heart button
                        var isLiked = false;
                        var likeBtn = document.querySelector('#now-playing-like-btn');
                        if (likeBtn) {
                            isLiked = likeBtn.classList.contains('active');
                        }
                        
                        var state = {
                            title: title,
                            artist: artist,
                            coverUrl: coverUrl,
                            isPlaying: isPlaying,
                            duration: duration,
                            position: position,
                            isLiked: isLiked
                        };
                        
                        if (window.AndroidBridge) {
                            window.AndroidBridge.updatePlaybackState(JSON.stringify(state));
                        }
                    }, 1000);
                """.trimIndent(), null)
            }

            private fun handleUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false // Let WebView handle standard web links natively
                }
                
                // If the URL is an intent:// or app link (often used in auth fallbacks), try to launch it externally
                return try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }

        // Handle Deep Linking / App Links
        val appLinkIntent = intent
        val appLinkData: Uri? = appLinkIntent.data
        if (appLinkData != null) {
            webView.loadUrl(appLinkData.toString())
        } else {
            // Load the target URL
            webView.loadUrl("https://monochrome.samidy.com/")
        }

        // Override back button to navigate WebView history instead of exiting the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If the OAuth popup is open, close it instead of navigating back
                if (popupWebView != null) {
                    closePopupWebView()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // No more history — exit the app normally
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val appLinkData: Uri? = intent.data
        if (appLinkData != null) {
            webView.loadUrl(appLinkData.toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mediaControlReceiver)
    }
}
