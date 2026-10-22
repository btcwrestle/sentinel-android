package com.samourai.sentinel.util;

import android.content.Context;

import com.samourai.sentinel.BuildConfig;
import com.samourai.sentinel.api.TlsPolicy;
import com.samourai.sentinel.tor.SentinelTorManager;

import java.net.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

public class WebUtil {


    private static WebUtil instance = null;
    private Context context = null;

    private WebUtil(Context context) {
        this.context = context;
    }

    public static WebUtil getInstance(Context ctx) {

        if (instance == null) {
            instance = new WebUtil(ctx);
        }

        return instance;
    }

    public static final String LBC_EXCHANGE_URL = "https://localbitcoins.com/bitcoinaverage/ticker-all-currencies/";
    public static final String BFX_EXCHANGE_URL = "https://api.bitfinex.com/v1/pubticker/btcusd";

    private static final int DefaultRequestRetry = 2;
    private static final int DefaultRequestTimeout = 60000;

    /**
     * #229: parsed-host onion check. The old gate was URL.contains("onion"),
     * a substring match, so "https://example.com/onion" also triggered the
     * trust-all relaxation. Unparseable URL fails closed (false).
     */
    private static boolean isOnionUrl(String URL) {
        try {
            String host = URI.create(URL).getHost();
            return host != null && host.toLowerCase().endsWith(".onion");
        } catch (Exception e) {
            return false;
        }
    }

    public String postURL(String URL, String urlParameters) throws Exception {

        if ( PrefsUtil.getInstance(context).getValue(PrefsUtil.ENABLE_TOR, false)) {
            return tor_postURL(URL,urlParameters);
        }


        final MediaType JSON
                = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, urlParameters.toString());

        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        // #240: policy on every builder, per the issue's acceptance - the
        // per-connection decision inside TlsPolicy keeps clearnet strict;
        // .onion would get the scoped relaxation.
        TlsPolicy.INSTANCE.install(builder);

        if (BuildConfig.DEBUG) {
            // #264/#96 (Arc B): BODY never comes back - bodies carry raw
            // tx hex and payload JSON. BASIC lines ride MaskedHttpLogger.
            builder.addInterceptor(new HttpLoggingInterceptor(new MaskedHttpLogger()).setLevel(HttpLoggingInterceptor.Level.BASIC));
        }

        Request request = new Request.Builder()
                .url(URL)
                .post(body)
                .build();

        try (Response response = builder.build().newCall(request).execute()) {
            if (response.body() == null) {
                return "";
            }
            return response.body().string();

        }
    }

    public String postURL(String URL, FormBody args) throws Exception {


        if ( PrefsUtil.getInstance(context).getValue(PrefsUtil.ENABLE_TOR, false)) {
            return tor_postURL(URL,args);
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        // #240: policy on every builder (see postURL above). The 60-HOUR
        // timeouts below are the inherited upstream shape - preserved
        // verbatim, flagged in the PR body as a follow-up candidate.
        TlsPolicy.INSTANCE.install(builder);
        builder.connectTimeout(60,TimeUnit.HOURS);
        builder.readTimeout(60,TimeUnit.HOURS);
        builder.readTimeout(60,TimeUnit.HOURS);

        if (BuildConfig.DEBUG) {
            // #264/#96 (Arc B): BODY never comes back (see postURL above).
            builder.addInterceptor(new HttpLoggingInterceptor(new MaskedHttpLogger()).setLevel(HttpLoggingInterceptor.Level.BASIC));
        }

        Request request = new Request.Builder()
                .url(URL)
                .post(args)
                .build();

        try (Response response = builder.build().newCall(request).execute()) {
            if (response.body() == null) {
                return "";
            }
            return response.body().string();

        }
    }


    public String getURL(String URL) throws Exception {

        if ( PrefsUtil.getInstance(context).getValue(PrefsUtil.ENABLE_TOR, false)) {
            return tor_getURL(URL);
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .readTimeout(90, TimeUnit.SECONDS);

        // #240: policy on every builder (see postURL above).
        TlsPolicy.INSTANCE.install(builder);

        if (BuildConfig.DEBUG) {
            // #264/#96 (Arc B): BODY never comes back (see postURL above).
            builder.addInterceptor(new HttpLoggingInterceptor(new MaskedHttpLogger()).setLevel(HttpLoggingInterceptor.Level.BASIC));
        }

        Request request = new Request.Builder()
                .url(URL)
                .build();

        try (Response response = builder.build().newCall(request).execute()) {
            if (response.body() == null) {
                return "";
            }
            return response.body().string();

        }

    }


    private String tor_getURL(String URL) throws Exception {

        // #240: this builder's Tor routing was commented out against the
        // pre-#135 TorManager API - every Tor-mode GET left DIRECT, the
        // same leak class #233 closed for the pairing client. Fail
        // closed: nothing proxy-less leaves a Tor-routed arm.
        Proxy torProxy = SentinelTorManager.INSTANCE.getProxy();
        if (torProxy == null) {
            throw new IllegalStateException("Tor-routed GET with no live SOCKS proxy (#240)");
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .proxy(torProxy)
                .connectTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS);

        if (BuildConfig.DEBUG) {
            // #264/#96 (Arc B): BODY never comes back (see postURL above).
            builder.addInterceptor(new HttpLoggingInterceptor(new MaskedHttpLogger()).setLevel(HttpLoggingInterceptor.Level.BASIC));
        }
        // #229: host-scoped policy instead of trust-all; clearnet HTTPS now
        // keeps normal verification even in the Tor code path.
        if (isOnionUrl(URL)) {
            TlsPolicy.INSTANCE.install(builder);
        }

        Request request = new Request.Builder()
                .url(URL)
                .build();

        try (Response response = builder.build().newCall(request).execute()) {
            if (response.body() == null) {
                return "";
            }
            return response.body().string();

        }

    }

    public String tor_postURL(String URL, String args) throws Exception {

        final MediaType JSON
                = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, args.toString());


        // #240: proxy restored (see tor_getURL) - fail closed, never direct.
        Proxy torProxy = SentinelTorManager.INSTANCE.getProxy();
        if (torProxy == null) {
            throw new IllegalStateException("Tor-routed POST with no live SOCKS proxy (#240)");
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .proxy(torProxy)
                .connectTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS);

        // #229: host-scoped policy instead of trust-all (same gate fix).
        if (isOnionUrl(URL)) {
            TlsPolicy.INSTANCE.install(builder);
        }

        if (BuildConfig.DEBUG) {
            // #264/#96 (Arc B): BODY never comes back (see postURL above).
            builder.addInterceptor(new HttpLoggingInterceptor(new MaskedHttpLogger()).setLevel(HttpLoggingInterceptor.Level.BASIC));
        }

        Request request = new Request.Builder()
                .url(URL)
                .post(body)
                .build();

        try (Response response = builder.build().newCall(request).execute()) {
            if (response.body() == null) {
                return "";
            }
            return response.body().string();

        }

    }

    public String tor_postURL(String URL, FormBody args) throws Exception {

        // #240: proxy restored (see tor_getURL) - fail closed, never direct.
        Proxy torProxy = SentinelTorManager.INSTANCE.getProxy();
        if (torProxy == null) {
            throw new IllegalStateException("Tor-routed POST with no live SOCKS proxy (#240)");
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.proxy(torProxy);

        // #229: host-scoped policy instead of trust-all (same gate fix).
        if (isOnionUrl(URL)) {
            TlsPolicy.INSTANCE.install(builder);
            builder.connectTimeout(90, TimeUnit.SECONDS);
            builder.readTimeout(90, TimeUnit.SECONDS);
        }

        if (BuildConfig.DEBUG) {
            // #264/#96 (Arc B): BODY never comes back (see postURL above).
            builder.addInterceptor(new HttpLoggingInterceptor(new MaskedHttpLogger()).setLevel(HttpLoggingInterceptor.Level.BASIC));
        }

        Request request = new Request.Builder()
                .url(URL)
                .post(args)
                .build();

        try (Response response = builder.build().newCall(request).execute()) {
            if (response.body() == null) {
                return "";
            }
            return response.body().string();

        }

    }

    public static String getAPIUrl(Context context) {

        return  "";
    }

}
