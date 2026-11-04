/*
 * Copyright 2026 OmniOne.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.omnione.did.proximity.sdk.core.logger;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class ProximityLogger {

    private static final String TAG = "PROXIMITY_LOG";
    private static final int MAX_CHUNK = 4000;

    private static volatile ProximityLogger instance;

    private volatile boolean enabled;

    private ProximityLogger() {}

    @NonNull
    public static ProximityLogger getInstance() {
        ProximityLogger current = instance;
        if (current != null) {
            return current;
        }
        synchronized (ProximityLogger.class) {
            if (instance == null) {
                instance = new ProximityLogger();
            }
            return instance;
        }
    }

    public void enable() {
        enabled = true;
    }

    public void disable() {
        enabled = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void d(@Nullable String message) {
        write(Log.DEBUG, message);
    }

    public void e(@Nullable String message) {
        write(Log.ERROR, message);
    }

    public void i(@Nullable String message) {
        write(Log.INFO, message);
    }

    public void v(@Nullable String message) {
        write(Log.VERBOSE, message);
    }

    private void write(int priority, @Nullable String message) {
        if (!enabled) {
            return;
        }
        String prefix = callerPrefix();
        for (String part : chunk(message)) {
            Log.println(priority, TAG, prefix + part);
        }
    }

    @NonNull
    static String callerPrefix() {
        return callerPrefix(Thread.currentThread().getStackTrace());
    }

    @NonNull
    static String callerPrefix(@NonNull StackTraceElement[] trace) {
        String self = ProximityLogger.class.getName();
        int deepest = -1;
        for (int i = 0; i < trace.length; i++) {
            if (trace[i].getClassName().equals(self)) {
                deepest = i;
            }
        }
        if (deepest < 0 || deepest + 1 >= trace.length) {
            return "";
        }
        StackTraceElement frame = trace[deepest + 1];
        return "[ " + sourceName(frame) + " :: " + frame.getMethodName() + " ] ";
    }

    @NonNull
    private static String sourceName(@NonNull StackTraceElement frame) {
        String file = frame.getFileName();
        if (file != null) {
            return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
        }
        String owner = frame.getClassName();
        return owner.substring(owner.lastIndexOf('.') + 1);
    }

    @NonNull
    static List<String> chunk(@Nullable String message) {
        String text = String.valueOf(message);
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < text.length(); start += MAX_CHUNK) {
            parts.add(text.substring(start, Math.min(start + MAX_CHUNK, text.length())));
        }
        if (parts.isEmpty()) {
            parts.add(text);
        }
        return parts;
    }
}
