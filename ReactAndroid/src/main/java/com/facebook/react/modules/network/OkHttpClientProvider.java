/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.modules.network;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import okhttp3.OkHttpClient;

/**
 * Helper class that provides the same OkHttpClient instance that will be used for all networking
 * requests.
 */
public class OkHttpClientProvider {

  // Centralized OkHttpClient for all networking requests.
  private static @Nullable OkHttpClient sClient;
  private static @Nullable OkHttpClient sClientForWebSocket;

  public static OkHttpClient getOkHttpClient() {
    if (sClient == null) {
      sClient = createClient();
    }
    return sClient;
  }

  // okhttp3 OkHttpClient is immutable
  // This allows app to init an OkHttpClient with custom settings.
  public static void replaceOkHttpClient(OkHttpClient client) {
    sClient = client;
  }

  public static OkHttpClient getOkHttpClientForWebSocket() {
    if (sClientForWebSocket == null) {
      sClientForWebSocket = createClientForWebSocket();
    }
    return sClientForWebSocket;
  }

  public static void replaceOkHttpClientForWebSocket(OkHttpClient client) {
    sClientForWebSocket = client;
  }

  private static OkHttpClient createClient() {
    // No timeouts by default
    return new OkHttpClient.Builder()
      .connectTimeout(0, TimeUnit.MILLISECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .writeTimeout(0, TimeUnit.MILLISECONDS)
      .cookieJar(new ReactCookieJarContainer())
      .build();
  }

  private static OkHttpClient createClientForWebSocket() {
    // No timeouts by default
    return new OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MINUTES) // Disable timeouts for read
      .build();
  }  
}
