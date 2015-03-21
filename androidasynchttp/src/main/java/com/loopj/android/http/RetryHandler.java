/*
    Android Asynchronous Http Client
    Copyright (c) 2011 James Smith <james@loopj.com>
    http://loopj.com

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
 */

/*
 Some of the retry logic in this class is heavily borrowed from the
 fantastic droid-fu project: https://github.com/donnfelker/droid-fu
 */

package com.loopj.android.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Iterator;

import javax.net.ssl.SSLException;

import android.os.SystemClock;
import android.util.Log;
import ch.boye.httpclientandroidlib.NoHttpResponseException;
import ch.boye.httpclientandroidlib.client.HttpRequestRetryHandler;
import ch.boye.httpclientandroidlib.client.methods.HttpUriRequest;
import ch.boye.httpclientandroidlib.protocol.ExecutionContext;
import ch.boye.httpclientandroidlib.protocol.HttpContext;

public class RetryHandler implements HttpRequestRetryHandler {
	private static final String TAG = "RetryHandler";
	private static HashSet<Class<?>> exceptionWhitelist = new HashSet<Class<?>>();
	private static HashSet<Class<?>> exceptionBlacklist = new HashSet<Class<?>>();

	static {
		// Retry if the server dropped connection on us
		exceptionWhitelist.add(NoHttpResponseException.class);
		// retry-this, since it may happens as part of a Wi-Fi to 3G failover
		exceptionWhitelist.add(UnknownHostException.class);
		// retry-this, since it may happens as part of a Wi-Fi to 3G failover
		exceptionWhitelist.add(SocketException.class);
		// socket timeout gets thrown (on my phone at least) on network switch, so we want to retry
		// but because this is also an InterruptedIOException we need to reverse the order we check the lists in		
		exceptionWhitelist.add(SocketTimeoutException.class);

		
		exceptionBlacklist.add(InterruptedIOException.class);
		// never retry SSL handshake failures
		exceptionBlacklist.add(SSLException.class);
	}

	private final int maxRetries;

	public RetryHandler(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
		boolean retry = true;

		Boolean b = (Boolean) context.getAttribute(ExecutionContext.HTTP_REQ_SENT);
		boolean sent = (b != null && b.booleanValue());

		if (executionCount > maxRetries) {
			// Do not retry if over max retry count
			retry = false;
		}
		else if (isInList(exceptionWhitelist, exception)) {
			// immediately retry if error is whitelisted
			retry = true;

		}
		else if (isInList(exceptionBlacklist, exception)) {
			// immediately cancel retry if the error is blacklisted
			retry = false;
		}
		else if (!sent) {
			// for most other errors, retry only if request hasn't been fully sent yet
			retry = true;
		}

		if (retry) {
			// resend all idempotent requests
			HttpUriRequest currentReq = (HttpUriRequest) context.getAttribute(ExecutionContext.HTTP_REQUEST);
			String requestType = currentReq.getMethod();
			retry = !requestType.equals("POST");
		}

		if (retry) {
			Log.w(TAG, "retryRequest, executionCount: " + executionCount, exception);
			int timerInterval = (int) (Math.pow(2, executionCount) * 1000);
			Log.v(TAG, "retryRequest, setting retry interval to: " + timerInterval);
			SystemClock.sleep(timerInterval);
		}
		else {

			exception.printStackTrace();
		}

		return retry;
	}

	protected boolean isInList(HashSet<Class<?>> list, Throwable error) {
		Iterator<Class<?>> itr = list.iterator();
		while (itr.hasNext()) {
			if (itr.next().isInstance(error)) {
				return true;
			}
		}
		return false;
	}
}