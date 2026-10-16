package app.wazegearslip.extension;

import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * Keeps Waze's speed from dropping to 0 for a moment while driving.
 *
 * Without Google Play services, Waze takes its locations straight from Android's LocationManager.
 * There is no fused provider to smooth them, so a fix without a speed, or a short gap between fixes,
 * shows up in Waze as 0 km/h. The patch sends every LocationManager.requestLocationUpdates /
 * removeUpdates call in Waze through here, and Waze's listener is wrapped in a [Wrapper] that
 * repairs such fixes before Waze sees them:
 *
 * - A fix without a speed (or with ~0) right after driving gets the speed computed from the
 *   distance to the previous fix. If the positions are too coarse for that, it keeps the last
 *   speed for a short time and then lets it fall no faster than a car can brake. A real stop
 *   is unaffected: the GPS reports the speed going down before it reaches 0.
 * - When no fix arrives at all for a moment, it can bridge the gap with a few fixes moved on
 *   along the last heading at the last speed (off when bridge seconds is 0).
 */
public final class SpeedFix {
    private static final String TAG = "WazeSpeedFix";

    /** Below this (m/s, ~10 km/h) the car counts as stopped or crawling; nothing is changed. */
    private static final float MOVING = 2.8f;
    /** A reported speed below this (m/s) counts as "no speed". */
    private static final float ZERO = 0.3f;
    /** Fixes at least this accurate (m) are good enough to compute a speed from. */
    private static final float ACCURATE = 25f;

    private static final Map<LocationListener, Wrapper> WRAPPERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean loaded;
    private static float holdSeconds = 2f;
    private static float maxBraking = 4f;
    private static int bridgeSeconds = 2;

    private SpeedFix() {
    }

    /** "hold;braking;bridge" -- the patch replaces this string with its option values. */
    private static String config() {
        return "2;4;2";
    }

    private static void load() {
        if (loaded) return;
        try {
            String[] parts = config().split(";");
            holdSeconds = Float.parseFloat(parts[0]);
            maxBraking = Float.parseFloat(parts[1]);
            bridgeSeconds = Integer.parseInt(parts[2]);
        } catch (Throwable t) {
            Log.w(TAG, "bad config, using defaults", t);
        }
        loaded = true;
    }

    private static LocationListener wrap(LocationListener listener) {
        if (listener == null || listener instanceof Wrapper) return listener;
        load();
        synchronized (WRAPPERS) {
            Wrapper wrapper = WRAPPERS.get(listener);
            if (wrapper == null) {
                wrapper = new Wrapper(listener);
                WRAPPERS.put(listener, wrapper);
            }
            return wrapper;
        }
    }

    private static LocationListener wrapped(LocationListener listener) {
        Wrapper wrapper = listener == null ? null : WRAPPERS.get(listener);
        if (wrapper != null) wrapper.stop();
        return wrapper != null ? wrapper : listener;
    }

    // The patch turns invoke-virtual LocationManager->X(args) into invoke-static SpeedFix->X(manager, args).

    public static void requestLocationUpdates(LocationManager manager, String provider, long minTime,
                                              float minDistance, LocationListener listener) {
        manager.requestLocationUpdates(provider, minTime, minDistance, wrap(listener));
    }

    public static void requestLocationUpdates(LocationManager manager, String provider, long minTime,
                                              float minDistance, LocationListener listener, Looper looper) {
        manager.requestLocationUpdates(provider, minTime, minDistance, wrap(listener), looper);
    }

    public static void requestLocationUpdates(LocationManager manager, String provider, long minTime,
                                              float minDistance, Executor executor, LocationListener listener) {
        manager.requestLocationUpdates(provider, minTime, minDistance, executor, wrap(listener));
    }

    public static void requestLocationUpdates(LocationManager manager, long minTime, float minDistance,
                                              Criteria criteria, LocationListener listener, Looper looper) {
        manager.requestLocationUpdates(minTime, minDistance, criteria, wrap(listener), looper);
    }

    public static void requestLocationUpdates(LocationManager manager, String provider, LocationRequest request,
                                              Executor executor, LocationListener listener) {
        manager.requestLocationUpdates(provider, request, executor, wrap(listener));
    }

    public static void removeUpdates(LocationManager manager, LocationListener listener) {
        manager.removeUpdates(wrapped(listener));
    }

    /** Repairs the fixes for one of Waze's listeners. */
    static final class Wrapper implements LocationListener {
        private final LocationListener delegate;

        /** The last real fix, and the last one handed to Waze (real or bridged). */
        private Location lastReal;
        private Location lastOut;
        /** The last speed Waze was given, and when (elapsed realtime) the GPS last reported one itself. */
        private float lastSpeed;
        private long lastGoodSpeedNanos;

        private Handler handler;
        private int bridged;
        private final Runnable bridge = this::bridge;

        Wrapper(LocationListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onLocationChanged(Location location) {
            if (location == null) {
                delegate.onLocationChanged(location);
                return;
            }
            Location out = location;
            try {
                out = repair(location);
                lastReal = location;
                rememberDelivered(out);
                bridged = 0;
                scheduleBridge();
            } catch (Throwable t) {
                Log.w(TAG, "repair failed, passing the fix on unchanged", t);
            }
            delegate.onLocationChanged(out);
        }

        private Location repair(Location in) {
            long now = in.getElapsedRealtimeNanos();
            boolean hasSpeed = in.hasSpeed() && in.getSpeed() >= ZERO;
            if (hasSpeed) {
                lastGoodSpeedNanos = now;
                return in;
            }
            Location prev = lastOut;
            if (prev == null || lastSpeed < MOVING) return in;
            float dt = (now - prev.getElapsedRealtimeNanos()) / 1e9f;
            if (dt <= 0f || dt > 10f) return in;

            // The speed from the distance to the last real fix, when both are precise enough.
            float measured = 0f;
            Location real = lastReal;
            if (real != null && in.hasAccuracy() && in.getAccuracy() <= ACCURATE
                    && real.hasAccuracy() && real.getAccuracy() <= ACCURATE) {
                float realDt = (now - real.getElapsedRealtimeNanos()) / 1e9f;
                if (realDt >= 0.5f) measured = real.distanceTo(in) / realDt;
            }
            // Otherwise: the last speed for a short while, then no faster a fall than braking.
            float sinceGood = (now - lastGoodSpeedNanos) / 1e9f;
            float held = sinceGood <= holdSeconds ? lastSpeed : lastSpeed - maxBraking * dt;
            float speed = Math.max(measured, held);
            if (speed < ZERO) return in;

            Location out = new Location(in);
            out.setSpeed(speed);
            if (!out.hasSpeedAccuracy()) out.setSpeedAccuracyMetersPerSecond(3f);
            Log.d(TAG, "fix without speed from " + in.getProvider() + ": " + Math.round(speed * 3.6f) + " km/h"
                    + (measured > 0f ? " (measured)" : " (held)"));
            return out;
        }

        private void rememberDelivered(Location out) {
            lastOut = out;
            lastSpeed = out.hasSpeed() ? out.getSpeed() : 0f;
        }

        private void scheduleBridge() {
            if (bridgeSeconds <= 0) return;
            if (handler == null) {
                Looper looper = Looper.myLooper();
                if (looper == null) return;
                handler = new Handler(looper);
            }
            handler.removeCallbacks(bridge);
            if (lastSpeed >= MOVING && lastOut != null && lastOut.hasBearing()) {
                handler.postDelayed(bridge, 1500);
            }
        }

        /** No fix for a moment while driving: move on along the last heading, at most bridgeSeconds times. */
        private void bridge() {
            Location prev = lastOut;
            if (prev == null || bridged >= bridgeSeconds || lastSpeed < MOVING) return;
            try {
                long nowNanos = SystemClock.elapsedRealtimeNanos();
                float dt = (nowNanos - prev.getElapsedRealtimeNanos()) / 1e9f;
                if (dt <= 0f || dt > 5f) return;
                double distance = lastSpeed * dt;
                double bearing = Math.toRadians(prev.getBearing());
                double lat = Math.toRadians(prev.getLatitude());
                double dLat = distance * Math.cos(bearing) / 6371000.0;
                double dLon = distance * Math.sin(bearing) / (6371000.0 * Math.cos(lat));

                Location out = new Location(prev);
                out.setLatitude(prev.getLatitude() + Math.toDegrees(dLat));
                out.setLongitude(prev.getLongitude() + Math.toDegrees(dLon));
                out.setTime(prev.getTime() + Math.round(dt * 1000));
                out.setElapsedRealtimeNanos(nowNanos);
                if (prev.hasAccuracy()) out.setAccuracy(prev.getAccuracy() + 5f * (bridged + 1));
                Bundle extras = out.getExtras() != null ? new Bundle(out.getExtras()) : new Bundle();
                extras.putBoolean("wazegearslip_bridged", true);
                out.setExtras(extras);

                bridged++;
                rememberDelivered(out);
                Log.d(TAG, "no fix for " + dt + " s, bridged " + bridged + "/" + bridgeSeconds);
                delegate.onLocationChanged(out);
                if (bridged < bridgeSeconds && handler != null) handler.postDelayed(bridge, 1000);
            } catch (Throwable t) {
                Log.w(TAG, "bridge failed", t);
            }
        }

        void stop() {
            if (handler != null) handler.removeCallbacks(bridge);
        }

        @Override
        public void onProviderEnabled(String provider) {
            delegate.onProviderEnabled(provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            delegate.onProviderDisabled(provider);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) {
            delegate.onStatusChanged(provider, status, extras);
        }

        @Override
        public void onFlushComplete(int requestCode) {
            delegate.onFlushComplete(requestCode);
        }
    }
}
