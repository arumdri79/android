package org.owntracks.android.services;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.greenrobot.eventbus.EventBus;
import org.owntracks.android.App;
import org.owntracks.android.BuildConfig;
import org.owntracks.android.R;
import org.owntracks.android.messages.MessageBase;
import org.owntracks.android.services.MessageProcessor.EndpointState;
import org.owntracks.android.services.worker.Scheduler;
import org.owntracks.android.support.Parser;
import org.owntracks.android.support.Preferences;
import org.owntracks.android.support.SocketFactory;
import org.owntracks.android.support.interfaces.ConfigurationIncompleteException;
import org.owntracks.android.support.preferences.OnModeChangedPreferenceChangedListener;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.X509TrustManager;

import okhttp3.CacheControl;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;

public class MessageProcessorEndpointHttp extends MessageProcessorEndpoint implements OnModeChangedPreferenceChangedListener {
    public static final int MODE_ID = 3;

    // Headers according to https://github.com/owntracks/recorder#http-mode
    static final String HEADER_USERNAME = "X-Limit-U";
    static final String HEADER_DEVICE = "X-Limit-D";
    private static final String HEADER_USERAGENT = "User-Agent";
    static final String METHOD = "POST";

    static final String HEADER_AUTHORIZATION = "Authorization";

    private static String httpEndpointHeaderUser = "";
    private static String httpEndpointHeaderDevice = "";
    private static String httpEndpointHeaderPassword = "";

    private static OkHttpClient mHttpClient;
    private static final MediaType JSON  = MediaType.parse("application/json; charset=utf-8");

    public static final String USERAGENT = "Owntracks/"+ BuildConfig.VERSION_CODE;
    private static final String HTTPTOPIC = "owntracks/http/";

    private Preferences preferences;
    private Parser parser;
    private Scheduler scheduler;
    private HttpUrl httpEndpoint;

    public MessageProcessorEndpointHttp(MessageProcessor messageProcessor, Parser parser, Preferences preferences, Scheduler scheduler, EventBus eventBus) {
        super(messageProcessor);
        this.parser = parser;
        this.preferences = preferences;
        this.scheduler = scheduler;

        preferences.registerOnPreferenceChangedListener(this);
        loadEndpointUrl();

    }

    @Override
    public void onCreateFromProcessor() {
        try {
            checkConfigurationComplete();
        } catch (ConfigurationIncompleteException e) {
            messageProcessor.onEndpointStateChanged(EndpointState.ERROR_CONFIGURATION.withError(e));
        }
    }

    @Nullable
    private SocketFactory getSocketFactory() {
        String tlsCaCrt = preferences.getTlsCaCrt();
        String tlsClientCrt = preferences.getTlsClientCrt();

        if(tlsCaCrt.length() == 0 && tlsClientCrt.length() == 0) {
            return null;
        }

        SocketFactory.SocketFactoryOptions socketFactoryOptions = new SocketFactory.SocketFactoryOptions();

        if (tlsCaCrt.length() > 0) {
            try {
                socketFactoryOptions.withCaInputStream(App.getContext().openFileInput(tlsCaCrt));
            } catch (FileNotFoundException e) {
                Timber.e(e);
                return null;
            }
        }

        if (tlsClientCrt.length() > 0)	{
            try {
                socketFactoryOptions.withClientP12InputStream(App.getContext().openFileInput(tlsClientCrt)).withClientP12Password(preferences.getTlsClientCrtPassword());
            } catch (FileNotFoundException e1) {
                Timber.e(e1);
                return null;
            }
        }

        try {
            return new SocketFactory(socketFactoryOptions);
        } catch (Exception e) {
            return null;
        }
    }

    private void loadHTTPClient() {
        mHttpClient = createHttpClient();
    }

    private OkHttpClient getHttpClient() {
        if(preferences.getDontReuseHttpClient()) {
            return createHttpClient();
        }

        if(mHttpClient == null)
            mHttpClient = createHttpClient();

        return mHttpClient;
    }



    private OkHttpClient createHttpClient() {
        Timber.d("creating new HTTP client instance");
        SocketFactory f = getSocketFactory();
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(1, 1, TimeUnit.MICROSECONDS))
                .retryOnConnectionFailure(false)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .cache(null);

        if(f != null) {
            builder.sslSocketFactory(f, (X509TrustManager) f.getTrustManagers()[0]);
        }

        return builder.build();

    }


    private void loadEndpointUrl() {
        try {
            httpEndpointHeaderUser = preferences.getUsername();
            httpEndpointHeaderDevice = preferences.getDeviceId();

            httpEndpoint = HttpUrl.get(preferences.getUrl());

            if(!httpEndpoint.username().isEmpty() && !httpEndpoint.password().isEmpty()) {
                httpEndpointHeaderUser = httpEndpoint.username();
                httpEndpointHeaderPassword = httpEndpoint.password();
            } else if(!preferences.getPassword().trim().equals("")) {
                httpEndpointHeaderPassword = preferences.getPassword();
            }

            messageProcessor.onEndpointStateChanged(EndpointState.IDLE);
        } catch (IllegalArgumentException e) {
            httpEndpoint = null;
            messageProcessor.onEndpointStateChanged(EndpointState.ERROR_CONFIGURATION.withError(e));
        }
    }

    @Nullable
    Request getRequest(MessageBase message) {
        try {
            this.checkConfigurationComplete();
        } catch (ConfigurationIncompleteException e) {
            return null;
        }
        Timber.d("url:%s, messageId:%s", this.httpEndpoint, message.getMessageId());

        String body;
        try {
            body = message.toJson(parser);
        } catch (IOException e) { // Message serialization failed. This shouldn't happen.
            messageProcessor.onEndpointStateChanged(EndpointState.ERROR.withMessage(e.getMessage()));
            return null;
        }


        // Any exception here (invalid header value, invalid URL, etc) will persist for all future messages until configuration is fixed.
        // Setting httpEndpoint to null will make sure no message can be send until the problem is corrected.
        try {
            Request.Builder request = new Request.Builder().url(this.httpEndpoint).header(HEADER_USERAGENT,USERAGENT).method(METHOD, RequestBody.create(JSON, body));

            if(isSet(httpEndpointHeaderUser) && isSet(httpEndpointHeaderPassword)) {
                request.header(HEADER_AUTHORIZATION, Credentials.basic(httpEndpointHeaderUser, httpEndpointHeaderPassword));
            }

            if (isSet(httpEndpointHeaderUser)) {
                request.header(HEADER_USERNAME, httpEndpointHeaderUser);
            }

            if (isSet(httpEndpointHeaderDevice)) {
                request.header(HEADER_DEVICE, httpEndpointHeaderDevice);
            }


            request.cacheControl(CacheControl.FORCE_NETWORK);
            return request.build();
        } catch (Exception e) {
            Timber.e(e,"invalid header specified");
            messageProcessor.onEndpointStateChanged(EndpointState.ERROR_CONFIGURATION.withError(e));
            httpEndpoint = null;
            return null;
        }
    }


    private static boolean isSet(String str) {
        return str != null && str.length() > 0;
    }

    void sendMessage(MessageBase message) {
        long messageId = message.getMessageId();
        Request request = getRequest(message);
        if(request == null) {
            messageProcessor.onMessageDeliveryFailedFinal(message.getMessageId());
            return;
        }

        try {
            Response response = getHttpClient().newCall(request).execute();
            // Message was send. Handle delivered message
            if((response.isSuccessful())) {
                Timber.d("request was successful: %s",response);
                // Handle response
                if(response.body() != null ) {
                    try {

                        MessageBase[] result = parser.fromJson(response.body().byteStream());
                        messageProcessor.onEndpointStateChanged(EndpointState.IDLE.withMessage("Response " + response.code() + ", " + result.length));

                        for (MessageBase aResult : result) {
                            onMessageReceived(aResult);
                        }
                    } catch (JsonProcessingException e ) {
                        Timber.e("error:JsonParseException responseCode:%s", response.code());
                        messageProcessor.onEndpointStateChanged(EndpointState.IDLE.withMessage("HTTP " +response.code() + ", JsonParseException"));
                    } catch (Parser.EncryptionException e) {
                        Timber.e("error:JsonParseException responseCode:%s", response.code());
                        messageProcessor.onEndpointStateChanged(EndpointState.ERROR.withMessage("HTTP: "+response.code() + ", EncryptionException"));
                    }

                }
                response.close();
            // Server could be contacted but returned non success HTTP code
            } else {
                Timber.e("request was not successful. HTTP code %s", response.code());
                messageProcessor.onEndpointStateChanged(EndpointState.ERROR.withMessage("HTTP code "+response.code() ));
                messageProcessor.onMessageDeliveryFailed(messageId);
                response.close();
                return;
            }
        // Message was not send
        } catch (Exception e) {
            Timber.e(e,"error:IOException. Delivery failed ");
            messageProcessor.onEndpointStateChanged(EndpointState.ERROR.withError(e));
            messageProcessor.onMessageDeliveryFailed(messageId);
            return;
        }
        messageProcessor.onMessageDelivered(message);
    }

    @Override
    public void onDestroy() {
        scheduler.cancelHttpTasks();
        preferences.unregisterOnPreferenceChangedListener(this);

    }

    @Override
    public void onAttachAfterModeChanged() {
        //NOOP
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (
                preferences.getPreferenceKey(R.string.preferenceKeyURL).equals(key)
                        || preferences.getPreferenceKey(R.string.preferenceKeyUsername).equals(key)
                        || preferences.getPreferenceKey(R.string.preferenceKeyPassword).equals(key)
                        || preferences.getPreferenceKey(R.string.preferenceKeyDeviceId).equals(key))
            loadEndpointUrl();
        else if (preferences.getPreferenceKey(R.string.preferenceKeyTLSClientCrt).equals(key)
                || preferences.getPreferenceKey(R.string.preferenceKeyTLSClientCrtPassword).equals(key)
                || preferences.getPreferenceKey(R.string.preferenceKeyTLSCaCrt).equals(key))
            mHttpClient = null;
    }

    @Override
    public void checkConfigurationComplete() throws ConfigurationIncompleteException {
        if (this.httpEndpoint==null)
        {
            throw new ConfigurationIncompleteException("HTTP Endpoint is missing");
        }
    }

    @Override
    int getModeId() {
        return MODE_ID;
    }

    @Override
    protected MessageBase onFinalizeMessage(MessageBase message) {
        // Build pseudo topic based on tid
        if(message.hasTid()) {
            message.setTopic(HTTPTOPIC + message.getTid());
        }
        return message;
    }
}
