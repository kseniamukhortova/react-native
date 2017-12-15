/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.views.webview;

import javax.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.lang.AssertionError;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Picture;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.CookieManager;

import com.facebook.common.logging.FLog;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.build.ReactBuildConfig;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.ContentSizeChangeEvent;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.webview.events.TopLoadingErrorEvent;
import com.facebook.react.views.webview.events.TopLoadingFinishEvent;
import com.facebook.react.views.webview.events.TopLoadingStartEvent;
import com.facebook.react.views.webview.events.TopMessageEvent;

import org.json.JSONObject;
import org.json.JSONException;

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.app.Activity;

/**
 * Manages instances of {@link WebView}
 *
 * Can accept following commands:
 *  - GO_BACK
 *  - GO_FORWARD
 *  - RELOAD
 *
 * {@link WebView} instances could emit following direct events:
 *  - topLoadingFinish
 *  - topLoadingStart
 *  - topLoadingError
 *
 * Each event will carry the following properties:
 *  - target - view's react tag
 *  - url - url set for the webview
 *  - loading - whether webview is in a loading state
 *  - title - title of the current page
 *  - canGoBack - boolean, whether there is anything on a history stack to go back
 *  - canGoForward - boolean, whether it is possible to request GO_FORWARD command
 */
@ReactModule(name = ReactWebViewManager.REACT_CLASS)
public class ReactWebViewManager extends SimpleViewManager<WebView> {

  protected static final String REACT_CLASS = "RCTWebView";

  protected static final String HTML_ENCODING = "UTF-8";
  protected static final String HTML_MIME_TYPE = "text/html";
  protected static final String BRIDGE_NAME = "__REACT_WEB_VIEW_BRIDGE";

  protected static final String HTTP_METHOD_POST = "POST";

  public static final int COMMAND_GO_BACK = 1;
  public static final int COMMAND_GO_FORWARD = 2;
  public static final int COMMAND_RELOAD = 3;
  public static final int COMMAND_STOP_LOADING = 4;
  public static final int COMMAND_POST_MESSAGE = 5;
  public static final int COMMAND_INJECT_JAVASCRIPT = 6;

  // Use `webView.loadUrl("about:blank")` to reliably reset the view
  // state and release page resources (including any running JavaScript).
  protected static final String BLANK_URL = "about:blank";

  protected WebViewConfig mWebViewConfig;
  protected @Nullable WebView.PictureListener mPictureListener;

  private static final int REQUEST_SELECT_FILE = 100;
  private static final int FILECHOOSER_RESULTCODE = 1;

  protected static class ReactWebViewClient extends WebViewClient {

    protected boolean mLastLoadFailed = false;
    protected @Nullable ReadableArray mUrlPrefixesForDefaultIntent;

    @Override
    public void onPageFinished(WebView webView, String url) {
      super.onPageFinished(webView, url);

      if (!mLastLoadFailed) {
        ReactWebView reactWebView = (ReactWebView) webView;
        reactWebView.callInjectedJavaScript();
        reactWebView.linkBridge();
        emitFinishEvent(webView, url);
      }
    }

    @Override
    public void onPageStarted(WebView webView, String url, Bitmap favicon) {
      super.onPageStarted(webView, url, favicon);
      mLastLoadFailed = false;

      dispatchEvent(
          webView,
          new TopLoadingStartEvent(
              webView.getId(),
              createWebViewEvent(webView, url)));
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        boolean useDefaultIntent = false;
        if (mUrlPrefixesForDefaultIntent != null && mUrlPrefixesForDefaultIntent.size() > 0) {
          ArrayList<Object> urlPrefixesForDefaultIntent =
              mUrlPrefixesForDefaultIntent.toArrayList();
          for (Object urlPrefix : urlPrefixesForDefaultIntent) {
            if (url.startsWith((String) urlPrefix)) {
              useDefaultIntent = true;
              break;
            }
          }
        }
        String partialUrl = "play.google.com/store/apps/details?id=";
        if (!useDefaultIntent && url.contains(partialUrl)) {
          int pos = url.indexOf(partialUrl) + partialUrl.length();
          String appId = url.substring(pos);
          try {
              // open the google play app
              Intent intent = new Intent(Intent.ACTION_VIEW);
              intent.setData(Uri.parse("market://details?id=" + appId));
              view.getContext().startActivity(intent);
              return true;  // we overrode the url load

          } catch (ActivityNotFoundException e) {
              //no google play app, load URL in device browser
              Uri uri = Uri.parse(url);
              view.loadUrl("https://play.google.com/store/apps/details?" + uri.getQuery());
              return false;
          }
        }
        if (!useDefaultIntent &&
            (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.equals("about:blank"))) {
          return false;
        } else {
          try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            view.getContext().startActivity(intent);
          } catch (ActivityNotFoundException e) {
            FLog.w(ReactConstants.TAG, "activity not found to handle uri scheme for: " + url, e);
          }
          return true;
        }
    }

    @Override
    public void onReceivedError(
        WebView webView,
        int errorCode,
        String description,
        String failingUrl) {
      super.onReceivedError(webView, errorCode, description, failingUrl);
      mLastLoadFailed = true;

      // In case of an error JS side expect to get a finish event first, and then get an error event
      // Android WebView does it in the opposite way, so we need to simulate that behavior
      emitFinishEvent(webView, failingUrl);

      WritableMap eventData = createWebViewEvent(webView, failingUrl);
      eventData.putDouble("code", errorCode);
      eventData.putString("description", description);

      dispatchEvent(
          webView,
          new TopLoadingErrorEvent(webView.getId(), eventData));
    }

    protected void emitFinishEvent(WebView webView, String url) {
      dispatchEvent(
          webView,
          new TopLoadingFinishEvent(
              webView.getId(),
              createWebViewEvent(webView, url)));
    }

    protected WritableMap createWebViewEvent(WebView webView, String url) {
      WritableMap event = Arguments.createMap();
      event.putDouble("target", webView.getId());
      // Don't use webView.getUrl() here, the URL isn't updated to the new value yet in callbacks
      // like onPageFinished
      event.putString("url", url);
      event.putBoolean("loading", !mLastLoadFailed && webView.getProgress() != 100);
      event.putString("title", webView.getTitle());
      event.putBoolean("canGoBack", webView.canGoBack());
      event.putBoolean("canGoForward", webView.canGoForward());
      return event;
    }

    public void setUrlPrefixesForDefaultIntent(ReadableArray specialUrls) {
      mUrlPrefixesForDefaultIntent = specialUrls;
    }
  }

  /**
   * Subclass of {@link WebView} that implements {@link LifecycleEventListener} interface in order
   * to call {@link WebView#destroy} on activty destroy event and also to clear the client
   */
  protected static class ReactWebView extends WebView implements LifecycleEventListener, ActivityEventListener {
    protected @Nullable String injectedJS;
    protected boolean messagingEnabled = false;
    protected @Nullable ReactWebViewClient mReactWebViewClient;

    protected class ReactWebViewBridge {
      ReactWebView mContext;

      ReactWebViewBridge(ReactWebView c) {
        mContext = c;
      }

      @JavascriptInterface
      public void postMessage(String message) {
        mContext.onMessage(message);
      }
    }

    private ValueCallback<Uri> mUploadMessage;
    private ValueCallback<Uri[]> mUploadMessages;
    private ThemedReactContext mReactContext;
    /**
     * WebView must be created with an context of the current activity
     *
     * Activity Context is required for creation of dialogs internally by WebView
     * Reactive Native needed for access to ReactNative internal system functionality
     *
     */
    public ReactWebView(ThemedReactContext reactContext) {
      super(reactContext);
      mReactContext = reactContext;

      if (17 <= Build.VERSION.SDK_INT && Build.VERSION.SDK_INT <= 19) {
        this.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
      }
    }

    public ReactContext getReactContext() {
      return mReactContext;
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
      FLog.w(ReactConstants.TAG, "onActivityResult requestCode: " + requestCode + ", resultCode: ", resultCode);

      if (requestCode == REQUEST_SELECT_FILE) {
          if (mUploadMessages == null) {
              return;
          }
          mUploadMessages.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
          mUploadMessages = null;
      } else if (requestCode == FILECHOOSER_RESULTCODE) {
        if (null == mUploadMessage) {
          return;
        }
        // Use MainActivity.RESULT_OK if you're implementing WebView inside Fragment
        // Use RESULT_OK only if you're implementing WebView inside an Activity
        Uri result = intent == null || resultCode != activity.RESULT_OK ? null : intent.getData();
        mUploadMessage.onReceiveValue(result);
        mUploadMessage = null;
      } else {
          FLog.w(ReactConstants.TAG, "Failed to Upload Image");
      }
    }

    @Override
    public void onNewIntent(Intent intent) {
      // do nothing
    }

    @Override
    public void onHostResume() {
      // do nothing
    }

    @Override
    public void onHostPause() {
      // do nothing
    }

    @Override
    public void onHostDestroy() {
      cleanupCallbacksAndDestroy();
    }

    @Override
    public void setWebViewClient(WebViewClient client) {
      super.setWebViewClient(client);
      mReactWebViewClient = (ReactWebViewClient)client;
    }

    public @Nullable ReactWebViewClient getReactWebViewClient() {
      return mReactWebViewClient;
    }

    public void setInjectedJavaScript(@Nullable String js) {
      injectedJS = js;
    }

    protected ReactWebViewBridge createReactWebViewBridge(ReactWebView webView) {
      return new ReactWebViewBridge(webView);
    }

    public void setMessagingEnabled(boolean enabled) {
      if (messagingEnabled == enabled) {
        return;
      }

      messagingEnabled = enabled;
      if (enabled) {
        addJavascriptInterface(createReactWebViewBridge(this), BRIDGE_NAME);
        linkBridge();
      } else {
        removeJavascriptInterface(BRIDGE_NAME);
      }
    }

    public void callInjectedJavaScript() {
      if (getSettings().getJavaScriptEnabled() &&
          injectedJS != null &&
          !TextUtils.isEmpty(injectedJS)) {
        loadUrl("javascript:(function() {\n" + injectedJS + ";\n})();");
      }
    }

    public void linkBridge() {
      if (messagingEnabled) {
        if (ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
          // See isNative in lodash
          String testPostMessageNative = "String(window.postMessage) === String(Object.hasOwnProperty).replace('hasOwnProperty', 'postMessage')";
          evaluateJavascript(testPostMessageNative, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
              if (value.equals("true")) {
                FLog.w(ReactConstants.TAG, "Setting onMessage on a WebView overrides existing values of window.postMessage, but a previous value was defined");
              }
            }
          });
        }

        loadUrl("javascript:(" +
          "window.originalPostMessage = window.postMessage," +
          "window.postMessage = function(data) {" +
            BRIDGE_NAME + ".postMessage(String(data));" +
          "}" +
        ")");
      }
    }

    public void onMessage(String message) {
      dispatchEvent(this, new TopMessageEvent(this.getId(), message));
    }

    protected void cleanupCallbacksAndDestroy() {
      setWebViewClient(null);
      destroy();
    }

    public void setCustomWebChromeClient() {      
      setWebChromeClient(new CustomWebChromeClient(this));  
    }

    public class CustomWebChromeClient extends WebChromeClient {
      ReactWebView mWebView;

      public CustomWebChromeClient(ReactWebView webView) {
        super();
        mWebView = webView;
      }

      @Override
      public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        callback.invoke(origin, true, false);
      }

      // Android 2.2 (API level 8) up to Android 2.3 (API level 10)
      protected void openFileChooser(ValueCallback<Uri> uploadMsg) {
        FLog.w(ReactConstants.TAG, "openFileChooser(1) Android 2.2-2.3");

        mUploadMessage = uploadMsg;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        tryStartActivityForResult(Intent.createChooser(i, "File Chooser"), FILECHOOSER_RESULTCODE);
      }

      // Android 3.0 (API level 11) up to Android 4.0 (API level 15)
      // onActivityResult attached before constructor
      protected void openFileChooser(ValueCallback uploadMsg, String acceptType) {
        FLog.w(ReactConstants.TAG, "openFileChooser(2) Android 3.0-4.0");

        mUploadMessage = uploadMsg;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        tryStartActivityForResult(Intent.createChooser(i, "File Browser"), FILECHOOSER_RESULTCODE);
      }

      // Android 4.1 (API level 16) up to Android 4.3 (API level 18)
      protected void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
        FLog.w(ReactConstants.TAG, "openFileChooser(3) Android 4.1-4.3");

        mUploadMessage = uploadMsg;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        tryStartActivityForResult(Intent.createChooser(intent, "File Browser"), FILECHOOSER_RESULTCODE);
      }

      // For Lollipop 5.0+ Devices
      public boolean onShowFileChooser(WebView mWebView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
        FLog.w(ReactConstants.TAG, "onShowFileChooser Android 5.0+");

        if (mUploadMessages != null) {
          mUploadMessages.onReceiveValue(null);
          mUploadMessages = null;
        }

        mUploadMessages = filePathCallback;
        Intent intent = fileChooserParams.createIntent();
        tryStartActivityForResult(intent, REQUEST_SELECT_FILE);
        return true;
      }

      private boolean tryStartActivityForResult(Intent intent, int code) {
        try {
          ReactContext reactContext = ((ReactContext) mWebView.getContext());
          reactContext.startActivityForResult(intent, code, null);
        } catch (ActivityNotFoundException | AssertionError e) {
          mUploadMessages = null;
          FLog.w(ReactConstants.TAG, "Cannot Open File Chooser: " + e.toString());
          return false;
        }
        return true;
      }
    }
  }

  public ReactWebViewManager() {
    mWebViewConfig = new WebViewConfig() {
      public void configWebView(WebView webView) {
      }
    };
  }

  public ReactWebViewManager(WebViewConfig webViewConfig) {
    mWebViewConfig = webViewConfig;
  }

  @Override
  public String getName() {
    return REACT_CLASS;
  }

  protected ReactWebView createReactWebViewInstance(ThemedReactContext reactContext) {
    return new ReactWebView(reactContext);
  }

  @Override
  protected WebView createViewInstance(ThemedReactContext reactContext) {
    ReactWebView webView = createReactWebViewInstance(reactContext);
    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onConsoleMessage(ConsoleMessage message) {
        if (ReactBuildConfig.DEBUG) {
          return super.onConsoleMessage(message);
        }
        // Ignore console logs in non debug builds.
        return true;
      }

      @Override
      public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        callback.invoke(origin, true, false);
      }
    });
    reactContext.addActivityEventListener(webView);
    reactContext.addLifecycleEventListener(webView);
    mWebViewConfig.configWebView(webView);
    webView.getSettings().setAllowFileAccess(true);
    webView.getSettings().setBuiltInZoomControls(true);
    webView.getSettings().setDisplayZoomControls(false);
    webView.getSettings().setDomStorageEnabled(true);

    // Fixes broken full-screen modals/galleries due to body height being 0.
    webView.setLayoutParams(
            new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

    if (ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      WebView.setWebContentsDebuggingEnabled(true);
    }

    return webView;
  }

  @ReactProp(name = "javaScriptEnabled")
  public void setJavaScriptEnabled(WebView view, boolean enabled) {
    view.getSettings().setJavaScriptEnabled(enabled);
  }

  @ReactProp(name = "thirdPartyCookiesEnabled")
  public void setThirdPartyCookiesEnabled(WebView view, boolean enabled) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      CookieManager.getInstance().setAcceptThirdPartyCookies(view, enabled);
    }
  }

  @ReactProp(name = "scalesPageToFit")
  public void setScalesPageToFit(WebView view, boolean enabled) {
    view.getSettings().setUseWideViewPort(!enabled);
  }

  @ReactProp(name = "domStorageEnabled")
  public void setDomStorageEnabled(WebView view, boolean enabled) {
    view.getSettings().setDomStorageEnabled(enabled);
  }

  @ReactProp(name = "userAgent")
  public void setUserAgent(WebView view, @Nullable String userAgent) {
    if (userAgent != null) {
      // TODO(8496850): Fix incorrect behavior when property is unset (uA == null)
      view.getSettings().setUserAgentString(userAgent);
    }
  }

  @ReactProp(name = "mediaPlaybackRequiresUserAction")
  public void setMediaPlaybackRequiresUserAction(WebView view, boolean requires) {
    view.getSettings().setMediaPlaybackRequiresUserGesture(requires);
  }

  @ReactProp(name = "allowUniversalAccessFromFileURLs")
  public void setAllowUniversalAccessFromFileURLs(WebView view, boolean allow) {
    view.getSettings().setAllowUniversalAccessFromFileURLs(allow);
  }

  @ReactProp(name = "saveFormDataDisabled")
  public void setSaveFormDataDisabled(WebView view, boolean disable) {
    view.getSettings().setSaveFormData(!disable);
  }

  @ReactProp(name = "injectedJavaScript")
  public void setInjectedJavaScript(WebView view, @Nullable String injectedJavaScript) {
    ((ReactWebView) view).setInjectedJavaScript(injectedJavaScript);
  }

  @ReactProp(name = "messagingEnabled")
  public void setMessagingEnabled(WebView view, boolean enabled) {
    ((ReactWebView) view).setMessagingEnabled(enabled);
  }

  @ReactProp(name = "source")
  public void setSource(WebView view, @Nullable ReadableMap source) {
    if (source != null) {
      if (source.hasKey("html")) {
        String html = source.getString("html");
        if (source.hasKey("baseUrl")) {
          view.loadDataWithBaseURL(
              source.getString("baseUrl"), html, HTML_MIME_TYPE, HTML_ENCODING, null);
        } else {
          view.loadData(html, HTML_MIME_TYPE, HTML_ENCODING);
        }
        return;
      }
      if (source.hasKey("uri")) {
        String url = source.getString("uri");
        String previousUrl = view.getUrl();
        if (previousUrl != null && previousUrl.equals(url)) {
          return;
        }
        if (source.hasKey("method")) {
          String method = source.getString("method");
          if (method.equals(HTTP_METHOD_POST)) {
            byte[] postData = null;
            if (source.hasKey("body")) {
              String body = source.getString("body");
              try {
                postData = body.getBytes("UTF-8");
              } catch (UnsupportedEncodingException e) {
                postData = body.getBytes();
              }
            }
            if (postData == null) {
              postData = new byte[0];
            }
            view.postUrl(url, postData);
            return;
          }
        }
        HashMap<String, String> headerMap = new HashMap<>();
        if (source.hasKey("headers")) {
          ReadableMap headers = source.getMap("headers");
          ReadableMapKeySetIterator iter = headers.keySetIterator();
          while (iter.hasNextKey()) {
            String key = iter.nextKey();
            if ("user-agent".equals(key.toLowerCase(Locale.ENGLISH))) {
              if (view.getSettings() != null) {
                view.getSettings().setUserAgentString(headers.getString(key));
              }
            } else {
              headerMap.put(key, headers.getString(key));
            }
          }
        }
        view.loadUrl(url, headerMap);
        return;
      }
    }
    view.loadUrl(BLANK_URL);
  }

  @ReactProp(name = "onContentSizeChange")
  public void setOnContentSizeChange(WebView view, boolean sendContentSizeChangeEvents) {
    if (sendContentSizeChangeEvents) {
      view.setPictureListener(getPictureListener());
    } else {
      view.setPictureListener(null);
    }
  }

  @ReactProp(name = "mixedContentMode")
  public void setMixedContentMode(WebView view, @Nullable String mixedContentMode) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      if (mixedContentMode == null || "never".equals(mixedContentMode)) {
        view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      } else if ("always".equals(mixedContentMode)) {
        view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
      } else if ("compatibility".equals(mixedContentMode)) {
        view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
      }
    }
  }

  @ReactProp(name = "urlPrefixesForDefaultIntent")
  public void setUrlPrefixesForDefaultIntent(
      WebView view,
      @Nullable ReadableArray urlPrefixesForDefaultIntent) {
    ReactWebViewClient client = ((ReactWebView) view).getReactWebViewClient();
    if (client != null && urlPrefixesForDefaultIntent != null) {
      client.setUrlPrefixesForDefaultIntent(urlPrefixesForDefaultIntent);
    }
  }

  @Override
  protected void addEventEmitters(ThemedReactContext reactContext, WebView view) {
    // Do not register default touch emitter and let WebView implementation handle touches
    view.setWebViewClient(new ReactWebViewClient());
  }

  @Override
  public @Nullable Map<String, Integer> getCommandsMap() {
    return MapBuilder.of(
        "goBack", COMMAND_GO_BACK,
        "goForward", COMMAND_GO_FORWARD,
        "reload", COMMAND_RELOAD,
        "stopLoading", COMMAND_STOP_LOADING,
        "postMessage", COMMAND_POST_MESSAGE,
        "injectJavaScript", COMMAND_INJECT_JAVASCRIPT
      );
  }

  @Override
  public void receiveCommand(WebView root, int commandId, @Nullable ReadableArray args) {
    switch (commandId) {
      case COMMAND_GO_BACK:
        root.goBack();
        break;
      case COMMAND_GO_FORWARD:
        root.goForward();
        break;
      case COMMAND_RELOAD:
        root.reload();
        break;
      case COMMAND_STOP_LOADING:
        root.stopLoading();
        break;
      case COMMAND_POST_MESSAGE:
        try {
          JSONObject eventInitDict = new JSONObject();
          eventInitDict.put("data", args.getString(0));
          root.loadUrl("javascript:(function () {" +
            "var event;" +
            "var data = " + eventInitDict.toString() + ";" +
            "try {" +
              "event = new MessageEvent('message', data);" +
            "} catch (e) {" +
              "event = document.createEvent('MessageEvent');" +
              "event.initMessageEvent('message', true, true, data.data, data.origin, data.lastEventId, data.source);" +
            "}" +
            "document.dispatchEvent(event);" +
          "})();");
        } catch (JSONException e) {
          throw new RuntimeException(e);
        }
        break;
      case COMMAND_INJECT_JAVASCRIPT:
        root.loadUrl("javascript:" + args.getString(0));
        break;
    }
  }

  @Override
  public void onDropViewInstance(WebView webView) {
    super.onDropViewInstance(webView);
    ((ThemedReactContext) webView.getContext()).removeLifecycleEventListener((ReactWebView) webView);
    ((ThemedReactContext) webView.getContext()).removeActivityEventListener((ReactWebView) webView);
    ((ReactWebView) webView).cleanupCallbacksAndDestroy();
  }

  protected WebView.PictureListener getPictureListener() {
    if (mPictureListener == null) {
      mPictureListener = new WebView.PictureListener() {
        @Override
        public void onNewPicture(WebView webView, Picture picture) {
          dispatchEvent(
            webView,
            new ContentSizeChangeEvent(
              webView.getId(),
              webView.getWidth(),
              webView.getContentHeight()));
        }
      };
    }
    return mPictureListener;
  }

  protected static void dispatchEvent(WebView webView, Event event) {
    ReactContext reactContext = (ReactContext) webView.getContext();
    EventDispatcher eventDispatcher =
      reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
    eventDispatcher.dispatchEvent(event);
  }
}
