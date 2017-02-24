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
import java.lang.AssertionError;
import java.util.HashMap;
import java.util.Map;

import android.graphics.Bitmap;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.webkit.GeolocationPermissions;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;

import com.facebook.common.logging.FLog;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.views.webview.events.TopLoadingErrorEvent;
import com.facebook.react.views.webview.events.TopLoadingFinishEvent;
import com.facebook.react.views.webview.events.TopLoadingStartEvent;
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
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
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
public class ReactWebViewManager extends SimpleViewManager<WebView> {

  private static final String REACT_CLASS = "RCTWebView";

  private static final String HTML_ENCODING = "UTF-8";
  private static final String HTML_MIME_TYPE = "text/html; charset=utf-8";

  private static final String HTTP_METHOD_POST = "POST";

  public static final int COMMAND_GO_BACK = 1;
  public static final int COMMAND_GO_FORWARD = 2;
  public static final int COMMAND_RELOAD = 3;
  public static final int COMMAND_STOP_LOADING = 4;

  // Use `webView.loadUrl("about:blank")` to reliably reset the view
  // state and release page resources (including any running JavaScript).
  private static final String BLANK_URL = "about:blank";

  private WebViewConfig mWebViewConfig;

  private static final int REQUEST_SELECT_FILE = 100;
  private static final int FILECHOOSER_RESULTCODE = 1;

  private static class ReactWebViewClient extends WebViewClient  {

    private boolean mLastLoadFailed = false;

    @Override
    public void onPageFinished(WebView webView, String url) {
      super.onPageFinished(webView, url);

      if (!mLastLoadFailed) {
        ReactWebView reactWebView = (ReactWebView) webView;
        reactWebView.callInjectedJavaScript();
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
        if (url.startsWith("http://") || url.startsWith("https://")) {
          return false;
        } else {
          Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)); 
          intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          view.getContext().startActivity(intent);   
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

    @Override
    public void doUpdateVisitedHistory(WebView webView, String url, boolean isReload) {
      super.doUpdateVisitedHistory(webView, url, isReload);

      dispatchEvent(
          webView,
          new TopLoadingStartEvent(
              webView.getId(),
              createWebViewEvent(webView, url)));
    }

    private void emitFinishEvent(WebView webView, String url) {
      dispatchEvent(
          webView,
          new TopLoadingFinishEvent(
              webView.getId(),
              createWebViewEvent(webView, url)));
    }

    private static void dispatchEvent(WebView webView, Event event) {
      ReactContext reactContext = (ReactContext) webView.getContext();
      EventDispatcher eventDispatcher =
          reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
      eventDispatcher.dispatchEvent(event);
    }

    private WritableMap createWebViewEvent(WebView webView, String url) {
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
  }

  /**
   * Subclass of {@link WebView} that implements {@link LifecycleEventListener} interface in order
   * to call {@link WebView#destroy} on activty destroy event and also to clear the client
   */
  private static class ReactWebView extends WebView implements LifecycleEventListener, ActivityEventListener {
    private @Nullable String injectedJS;

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
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
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
          Activity activity = ((ReactContext) getReactContext()).getCurrentActivity();
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

    public void setInjectedJavaScript(@Nullable String js) {
      injectedJS = js;
    }

    public void callInjectedJavaScript() {
      if (getSettings().getJavaScriptEnabled() &&
          injectedJS != null &&
          !TextUtils.isEmpty(injectedJS)) {
        loadUrl("javascript:(function() {\n" + injectedJS + ";\n})();");
      }
    }

    private void cleanupCallbacksAndDestroy() {
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

  @Override
  protected WebView createViewInstance(ThemedReactContext reactContext) {
    ReactWebView webView = new ReactWebView(reactContext);
    webView.setCustomWebChromeClient();
    reactContext.addActivityEventListener(webView);
    reactContext.addLifecycleEventListener(webView);
    mWebViewConfig.configWebView(webView);
    webView.getSettings().setAllowFileAccess(true);
    webView.getSettings().setBuiltInZoomControls(true);
    webView.getSettings().setDisplayZoomControls(false);

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

  @ReactProp(name = "injectedJavaScript")
  public void setInjectedJavaScript(WebView view, @Nullable String injectedJavaScript) {
    ((ReactWebView) view).setInjectedJavaScript(injectedJavaScript);
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
            headerMap.put(key, headers.getString(key));
          }
        }
        view.loadUrl(url, headerMap);
        return;
      }
    }
    view.loadUrl(BLANK_URL);
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
        "stopLoading", COMMAND_STOP_LOADING);
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
    }
  }

  @Override
  public void onDropViewInstance(WebView webView) {
    super.onDropViewInstance(webView);
    ((ThemedReactContext) webView.getContext()).removeLifecycleEventListener((ReactWebView) webView);
    ((ThemedReactContext) webView.getContext()).removeActivityEventListener((ReactWebView) webView);
    ((ReactWebView) webView).cleanupCallbacksAndDestroy();
  }
}
