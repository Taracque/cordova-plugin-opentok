package com.tokbox.cordova;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.opentok.android.AudioDeviceManager;
import com.opentok.android.BaseAudioDevice;
import com.opentok.android.Connection;
import com.opentok.android.OpentokError;
import com.opentok.android.Publisher;
import com.opentok.android.PublisherKit;
import com.opentok.android.Session;
import com.opentok.android.Stream;
import com.opentok.android.Stream.StreamVideoType;
import com.opentok.android.Subscriber;
import com.opentok.android.SubscriberKit;


public class OpenTokAndroidPlugin extends CordovaPlugin implements
Session.SessionListener, Session.ConnectionListener, Session.SignalListener,
PublisherKit.PublisherListener, Publisher.CameraListener, Session.StreamPropertiesListener {

    protected Session mSession;
    protected String mCamera = "front";
    public static final String TAG = "OTPlugin";
    public boolean sessionConnected;
    public RunnablePublisher myPublisher;
    public HashMap<String, CallbackContext> myEventListeners;
    public HashMap<String, Connection> connectionCollection;
    public HashMap<String, Stream> streamCollection;
    public HashMap<String, RunnableSubscriber> subscriberCollection;

    private static PowerManager.WakeLock wakeLock;
    private CallbackContext permissionsCallback;

    static JSONObject viewList = new JSONObject();

    public class RunnableUpdateViews implements Runnable {
        public JSONArray mProperty;
        public View mView;
        public ArrayList<RunnableUpdateViews> allStreamViews;


        public class CustomComparator implements Comparator<RunnableUpdateViews> {
            @Override
            public int compare(RunnableUpdateViews object1, RunnableUpdateViews object2) {
                return object2.getZIndex() - object1.getZIndex();
            }
        }

        public void updateZIndices() {
            allStreamViews = new ArrayList<RunnableUpdateViews>();
            for (Map.Entry<String, RunnableSubscriber> entry : subscriberCollection.entrySet()) {
                allStreamViews.add(entry.getValue());
            }
            if (myPublisher != null) {
                allStreamViews.add(myPublisher);
            }
            Collections.sort(allStreamViews, new CustomComparator());

            for (RunnableUpdateViews viewContainer : allStreamViews) {
                ViewGroup parent = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                if (null != parent) {
                    parent.removeView(viewContainer.mView);
                    parent.addView(viewContainer.mView);
                }
            }
        }

        public int getZIndex() {
            try {
                return mProperty.getInt(5);
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public void run() {
            try {
                Log.i(TAG, "updating view in ui runnable" + mProperty.toString());
                Log.i(TAG, "updating view in ui runnable " + mView.toString());

                float widthRatio, heightRatio;

                // Ratios are index 6 & 7 on TB.updateViews, 8 & 9 on subscribe event, and 9 & 10 on TB.initPublisher
                int ratioIndex;
                if (mProperty.get(6) instanceof Number) {
                    ratioIndex = 6;
                } else if (mProperty.get(8) instanceof Number) {
                    ratioIndex = 8;
                } else {
                    ratioIndex = 9;
                }

                widthRatio = (float) mProperty.getDouble(ratioIndex);
                heightRatio = (float) mProperty.getDouble(ratioIndex + 1);

                mView.setY(mProperty.getInt(1) * heightRatio);
                mView.setX(mProperty.getInt(2) * widthRatio);
                ViewGroup.LayoutParams params = mView.getLayoutParams();
                params.height = (int) (mProperty.getInt(4) * heightRatio);
                params.width = (int) (mProperty.getInt(3) * widthRatio);

                // remove status bar height if not in fullscreen
                if ((cordova.getActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) == 0) {
                    Rect rectangle = new Rect();
                    cordova.getActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(rectangle);
                    params.height = params.height - rectangle.top;
                }

                mView.setLayoutParams(params);
                updateZIndices();
            } catch (Exception e) {
                Log.i(TAG, "error when trying to retrieve properties while resizing properties");
            }
        }
    }

    public class RunnablePublisher extends RunnableUpdateViews implements
    PublisherKit.PublisherListener, Publisher.CameraListener {
        //  property contains: [name, position.top, position.left, width, height, zIndex, publishAudio, publishVideo, cameraName] )
        public Publisher mPublisher;

        public RunnablePublisher(JSONArray args) {
            this.mProperty = args;

            // prevent dialog box from showing because it causes crash
            SharedPreferences prefs = cordova.getActivity().getApplicationContext().getSharedPreferences("permissions",
                                                                                                         Context.MODE_PRIVATE);
            Editor edit = prefs.edit();
            edit.clear();
            edit.putBoolean("opentok.publisher.accepted", true);
            edit.apply();
        }

        public void setPropertyFromArray(JSONArray args) {
            this.mProperty = args;
        }

        public void startPublishing() {
            cordova.getActivity().runOnUiThread(this);
        }


        public void destroyPublisher() {
            ViewGroup parent = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
            parent.removeView(this.mView);
            if (this.mPublisher != null) {
                this.mPublisher.destroy();
                this.mPublisher = null;
            }
        }

        public void run() {
            Log.i(TAG, "view running on UIVIEW!!!");
            if (mPublisher == null) {
                ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                String publisherName;
                Boolean publishAudio = true;
                Boolean publishVideo = true;
                try {
                    publisherName = this.mProperty.getString(0);
                    publishAudio = compareStrings(this.mProperty.getString(6), "true");
                    publishVideo = compareStrings(this.mProperty.getString(7), "true");
                } catch (Exception e) {
                    publisherName = "Android-Publisher";
                }

                Publisher.Builder builder = new Publisher.Builder(cordova.getActivity().getApplicationContext());
                builder.name(publisherName);
                builder.audioTrack(publishAudio);
                builder.videoTrack(publishVideo);

                mPublisher = builder.build();
                mPublisher.setCameraListener(this);
                mPublisher.setPublisherListener(this);
                try {
                    // Camera is swapped in streamCreated event
                    if (compareStrings(this.mProperty.getString(7), "false")) {
                        mPublisher.setPublishVideo(false); // default is true
                    }
                    if (compareStrings(this.mProperty.getString(6), "false")) {
                        mPublisher.setPublishAudio(false); // default is true
                    }
                    Log.i(TAG, "all set up for publisher");
                } catch (Exception e) {
                    Log.i(TAG, "error when trying to retrieve publish audio/video property");
                }
                this.mView = mPublisher.getView();
                frame.addView(this.mView);
                mSession.publish(mPublisher);
            }
            super.run();
        }

        // event listeners
        @Override
        public void onError(PublisherKit arg0, OpentokError arg1) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onStreamCreated(PublisherKit arg0, Stream arg1) {
            Log.i(TAG, "publisher stream received");
            try {
                if ( (compareStrings(this.mProperty.getString(8), "back")) && ( mCamera.equals("front")) ) {
                    Log.i(TAG, "swapping camera");
                    mPublisher.cycleCamera(); // default is front
                    mCamera = "back";
                }
            } catch (Exception e) {
                Log.i(TAG, "error when trying to retrieve cameraName property");
            }
            streamCollection.put(arg1.getStreamId(), arg1);
            if (arg0.getPublishVideo()) {
                AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.SpeakerPhone);
                this.mView.setKeepScreenOn(true);
                cordova.getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.Handset);
            }
            triggerStreamCreated(arg1, "publisherEvents");
        }

        @Override
        public void onStreamDestroyed(PublisherKit arg0, Stream arg1) {
            if (arg1 != null) {
                streamCollection.remove(arg1.getStreamId());
                triggerStreamDestroyed(arg1, "publisherEvents");
            }
        }

        @Override
        public void onCameraChanged(Publisher arg0, int arg1) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onCameraError(Publisher arg0, OpentokError arg1) {
            // TODO Auto-generated method stub

        }

    }

    public class RunnableSubscriber extends RunnableUpdateViews implements
    SubscriberKit.SubscriberListener, SubscriberKit.VideoListener {
        //  property contains: [stream.streamId, position.top, position.left, width, height, subscribeToVideo, zIndex] )
        public Subscriber mSubscriber;
        public Stream mStream;

        public RunnableSubscriber(JSONArray args, Stream stream) {
            this.mProperty = args;
            mStream = stream;
            cordova.getActivity().runOnUiThread(this);
        }

        public void setPropertyFromArray(JSONArray args) {
            this.mProperty = args;
        }

        public void removeStreamView() {
            ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
            frame.removeView(this.mView);
            mSubscriber.destroy();
        }

        public void run() {
            if (mSubscriber == null) {
                Log.i(TAG, "NEW SUBSCRIBER BEING CREATED");

                Subscriber.Builder builder = new Subscriber.Builder(cordova.getActivity(), mStream);
                mSubscriber = builder.build();
                mSubscriber.setVideoListener(this);
                mSubscriber.setSubscriberListener(this);
                ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                this.mView = mSubscriber.getView();
                frame.addView(this.mView);
                mSession.subscribe(mSubscriber);

                boolean subToAudio = true;
                boolean subToVideo = false;
                try {
                    subToAudio = this.mProperty.getBoolean(6);
                    subToVideo = this.mProperty.getBoolean(7);
                } catch (JSONException e) {
                    Log.e(TAG, "JSONException" + e.getMessage());
                }
                mSubscriber.setSubscribeToAudio( subToAudio );
                mSubscriber.setSubscribeToVideo( subToVideo );
                if (subToVideo) {
                    AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.SpeakerPhone);
                } else {
                    AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.Handset);
                }

                Log.i(TAG, "subscriber view is added to parent view!");
            }
            super.run();
        }

        public void unsubscribe() {
            mSession.unsubscribe(mSubscriber);
            ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
            frame.removeView(this.mView);
            mSubscriber = null;
        }


        // listeners
        @Override
        public void onVideoDataReceived(SubscriberKit arg0) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onConnected(SubscriberKit arg0) {
            // TODO Auto-generated method stub
            JSONObject eventData = new JSONObject();
            String streamId = arg0.getStream().getStreamId();
            try {
                eventData.put("streamId", streamId);
                triggerJSEvent("sessionEvents", "subscribedToStream", eventData);
            } catch (JSONException e) {
                Log.e(TAG, "JSONException" + e.getMessage());
            }
            Log.i(TAG, "subscriber" + streamId + " is connected");
            this.run();
        }

        @Override
        public void onDisconnected(SubscriberKit arg0) {
            // TODO Auto-generated method stub
        }

        @Override
        public void onError(SubscriberKit arg0, OpentokError arg1) {
            JSONObject eventData = new JSONObject();
            String streamId = arg0.getStream().getStreamId();
            int errorCode = arg1.getErrorCode().getErrorCode();
            try {
                eventData.put("errorCode", errorCode);
                eventData.put("streamId", streamId);
                triggerJSEvent("sessionEvents", "subscribedToStream", eventData);
            } catch (JSONException e) {
                Log.e(TAG, "JSONException" + e.getMessage());
            }
            Log.e(TAG, "subscriber exception: " + arg1.getMessage() + ", stream id: " + arg0.getStream().getStreamId());
        }

        @Override
        public void onVideoDisableWarning(SubscriberKit arg0) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onVideoDisableWarningLifted(SubscriberKit arg0) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onVideoDisabled(SubscriberKit arg0, String arg1) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onVideoEnabled(SubscriberKit arg0, String arg1) {
            // TODO Auto-generated method stub

        }
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        Log.d(TAG, "Initialize Plugin");
        // By default, get a pointer to mainView and add mainView to the viewList as it always exists (hold cordova's view)
        if (!viewList.has("mainView")) {
            // Cordova view is not in the viewList so add it.
            try {
                viewList.put("mainView", webView);
                Log.d(TAG, "Found CordovaView ****** " + webView);
            } catch (JSONException e) {
                // Error handle (this should never happen!)
                Log.e(TAG, "Critical error. Failed to retrieve Cordova's view");
            }
        }

        // set OpenTok states
        sessionConnected = false;
        myEventListeners = new HashMap<String, CallbackContext>();
        connectionCollection = new HashMap<String, Connection>();
        streamCollection = new HashMap<String, Stream>();
        subscriberCollection = new HashMap<String, RunnableSubscriber>();

        super.initialize(cordova, webView);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.i(TAG, action);
        // TB Methods
        if (action.equals("initPublisher")) {
            myPublisher = new RunnablePublisher(args);

            callbackContext.success();
            return true;
        } else if (action.equals("destroyPublisher")) {
            if (myPublisher != null) {
                cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        myPublisher.destroyPublisher();
                        myPublisher = null;
                    }
                });

                callbackContext.success();
                return true;
            }
        } else if (action.equals("initSession")) {
            boolean isVideo = (args.length() == 3) && (args.getBoolean(2)); // have 3 arguments, and last one is true
            Context context = cordova.getActivity().getApplicationContext();

            /* set audio driver */
            OTDefaultAudioDevice otDefaultAudioDevice = new OTDefaultAudioDevice(context,isVideo);
            otDefaultAudioDevice.setOutputMode(isVideo?BaseAudioDevice.OutputMode.SpeakerPhone:BaseAudioDevice.OutputMode.Handset);
            AudioDeviceManager.setAudioDevice(otDefaultAudioDevice);

            Session.Builder builder = new Session.Builder(context, args.getString(0), args.getString(1));
            mSession = builder.build();

            Log.i(TAG, "created new session with data: " + args.toString());

            PowerManager powerManager = (PowerManager) cordova.getActivity().getSystemService(Context.POWER_SERVICE);
            int wakeLockMode = PowerManager.PARTIAL_WAKE_LOCK;

            if (isVideo) { // video call
                AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.SpeakerPhone);
            } else { // audio call
                AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.Handset);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                        wakeLockMode = PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK;
                    }
                }
            }
            /* wakeLock */
            wakeLock = powerManager.newWakeLock(wakeLockMode, TAG);
            wakeLock.acquire();

            mSession.setSessionListener(this);
            mSession.setConnectionListener(this);
            mSession.setSignalListener(this);
            mSession.setStreamPropertiesListener(this);
            // publisher methods
        } else if (action.equals("setCameraPosition")) {
            String cameraId = args.getString(0);
            if ((cameraId.equals("front")) && (!mCamera.equals("front"))) {
                myPublisher.mPublisher.cycleCamera();
                mCamera = "front";
            } else if ((cameraId.equals("back")) && (mCamera.equals("front"))) {
                myPublisher.mPublisher.cycleCamera();
                mCamera = "back";
            }
        } else if (action.equals("publishAudio")) {
            String val = args.getString(0);
            boolean publishAudio = true;
            if (val.equalsIgnoreCase("false")) {
                publishAudio = false;
            }
            Log.i(TAG, "setting publishAudio");
            myPublisher.mPublisher.setPublishAudio(publishAudio);
        } else if (action.equals("publishVideo")) {
            String val = args.getString(0);
            boolean publishVideo = true;
            if (val.equalsIgnoreCase("false")) {
                publishVideo = false;
            }
            Log.i(TAG, "setting publishVideo");
            myPublisher.mPublisher.setPublishVideo(publishVideo);

            // session Methods
        } else if (action.equals("addEvent")) {
            Log.i(TAG, "adding new event - " + args.getString(0));
            myEventListeners.put(args.getString(0), callbackContext);
        } else if (action.equals("connect")) {
            Log.i(TAG, "connect command called");
            mSession.connect(args.getString(0));
        } else if (action.equals("disconnect")) {
            mSession.disconnect();
        } else if (action.equals("publish")) {
            if (sessionConnected) {
                Log.i(TAG, "publisher is publishing");
                myPublisher.startPublishing();

                callbackContext.success();
                return true;
            }
        } else if (action.equals("signal")) {
            Connection c = connectionCollection.get(args.getString(2));
            if (c == null) {
                mSession.sendSignal(args.getString(0), args.getString(1));
            } else {
                mSession.sendSignal(args.getString(0), args.getString(1), c);
            }
        } else if (action.equals("unpublish")) {

        } else if (action.equals("unsubscribe")) {

            Log.i(TAG, "unsubscribe command called");
            Log.i(TAG, "unsubscribe data: " + args.toString());
            RunnableSubscriber runsub = subscriberCollection.get(args.getString(0));
            runsub.unsubscribe();
        } else if (action.equals("subscribe")) {
            Log.i(TAG, "subscribe command called");
            Log.i(TAG, "subscribe data: " + args.toString());
            Stream stream = streamCollection.get(args.getString(0));
            RunnableSubscriber runsub = new RunnableSubscriber(args, stream);
            subscriberCollection.put(stream.getStreamId(), runsub);
        } else if (action.equals("updateView")) {
            if (args.getString(0).equals("TBPublisher") && myPublisher != null && sessionConnected) {
                Log.i(TAG, "updating view for publisher");
                myPublisher.setPropertyFromArray(args);
                cordova.getActivity().runOnUiThread(myPublisher);
            } else {
                RunnableSubscriber runsub = subscriberCollection.get(args.getString(0));
                if (runsub != null) {
                    runsub.setPropertyFromArray(args);
                    cordova.getActivity().runOnUiThread(runsub);
                }
            }

            // Audio methods
        } else if (action.equals("setupAudioSession")) {
            if (AudioDeviceManager.getAudioDevice() != null) {
                if (args.getBoolean(0)) {
                    AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.SpeakerPhone);
                } else {
                    AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.Handset);
                }
            }
        } else if (action.equals("stopAudioSession")) {
        } else if (action.equals("loudSpeaker")) {
            Log.i(TAG, "loudSpeaker " + args.getBoolean(0));
            if (AudioDeviceManager.getAudioDevice() != null) {
                if (args.getBoolean(0)) {
                    AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.SpeakerPhone);
                } else {
                    AudioDeviceManager.getAudioDevice().setOutputMode(BaseAudioDevice.OutputMode.Handset);
                }
            } else {
                AudioManager am = (AudioManager) cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                am.setSpeakerphoneOn(args.getBoolean(0));
            }
        } else if (action.equals("requestAccess")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String checkPerm;
                int requestCode;

                if (args.getString(0).equals("camera")) {
                    checkPerm = Manifest.permission.CAMERA;
                    requestCode = 1;
                } else {
                    checkPerm = Manifest.permission.RECORD_AUDIO;
                    requestCode = 0;
                }

                if (!cordova.hasPermission(checkPerm)) {
                    permissionsCallback = callbackContext;
                    cordova.requestPermission(this, requestCode, checkPerm);
                } else {
                    callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, true));
                    callbackContext.success();
                }
            } else {
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, true));
                callbackContext.success();
            }
            return true;
        } else if (action.equals("exceptionHandler")) {

        }
        return true;
    }

    public void alertUser(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(cordova.getActivity());
        builder.setMessage(message).setTitle( cordova.getActivity().getApplicationInfo().name );
        AlertDialog dialog = builder.create();
        if (!cordova.getActivity().isFinishing()) {
            dialog.show();
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (permissionsCallback == null) {
            return;
        }

        if (permissions != null && permissions.length > 0) {
            boolean result = true;
            for(int i = 0; i<grantResults.length;i++) {
                result = result && (grantResults[i] == PackageManager.PERMISSION_GRANTED);
            }
            permissionsCallback.sendPluginResult(new PluginResult(PluginResult.Status.OK, result));
            permissionsCallback.success();
        } else {
            permissionsCallback.success();
        }
        permissionsCallback = null;
    }

    // sessionListener
    @Override
    public void onConnected(Session session) {
        Log.i(TAG, "session connected, triggering sessionConnected Event. My Cid is: " +
                session.getConnection().getConnectionId());
        sessionConnected = true;

        connectionCollection.put(session.getConnection().getConnectionId(), session.getConnection());

        JSONObject data = new JSONObject();
        try {
            data.put("status", "connected");
            JSONObject connection = createDataFromConnection(session.getConnection());
            data.put("connection", connection);
        } catch (JSONException e) {
        }
        triggerJSEvent("sessionEvents", "sessionConnected", data);
    }

    @Override
    public void onDisconnected(Session arg0) {
        sessionConnected = false;

        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ViewGroup parent = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                if (myPublisher != null) {
                    myPublisher.destroyPublisher();
                    myPublisher = null;
                }
                for (Map.Entry<String, RunnableSubscriber> entry : subscriberCollection.entrySet()) {
                    if (null != parent) {
                        parent.removeView(entry.getValue().mView);
                    }
                }
                cordova.getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });

        // release wakeLock
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        // delete all data and prevent updateviews from drawing non existent things
        subscriberCollection.clear();
        connectionCollection.clear();
        streamCollection.clear();

        JSONObject data = new JSONObject();
        try {
            data.put("reason", "clientDisconnected");
        } catch (JSONException e) {
        }

        triggerJSEvent("sessionEvents", "sessionDisconnected", data);
    }

    @Override
    public void onStreamDropped(Session arg0, Stream arg1) {
        Log.i(TAG, "session dropped stream");
        streamCollection.remove(arg1.getStreamId());
        RunnableSubscriber subscriber = subscriberCollection.get(arg1.getStreamId());
        if (subscriber != null) {
            subscriber.removeStreamView();
            subscriberCollection.remove(arg1.getStreamId());
        }

        triggerStreamDestroyed(arg1, "sessionEvents");
    }

    @Override
    public void onStreamReceived(Session arg0, Stream arg1) {
        Log.i(TAG, "stream received");
        streamCollection.put(arg1.getStreamId(), arg1);
        triggerStreamCreated(arg1, "sessionEvents");
    }

    @Override
    public void onError(Session arg0, OpentokError arg1) {
        // TODO Auto-generated method stub
        Log.e(TAG, "session exception: " + arg1.getMessage());
    }

    // connectionListener
    public void onConnectionCreated(Session arg0, Connection arg1) {
        Log.i(TAG, "connectionCreated");

        connectionCollection.put(arg1.getConnectionId(), arg1);

        JSONObject data = new JSONObject();
        try {
            JSONObject connection = createDataFromConnection(arg1);
            data.put("connection", connection);
        } catch (JSONException e) {
        }
        triggerJSEvent("sessionEvents", "connectionCreated", data);
    }

    public void onConnectionDestroyed(Session arg0, Connection arg1) {
        Log.i(TAG, "connection dropped: " + arg1.getConnectionId());

        connectionCollection.remove(arg1.getConnectionId());
        JSONObject data = new JSONObject();
        try {
            JSONObject connection = createDataFromConnection(arg1);
            data.put("connection", connection);
        } catch (JSONException e) {
        }
        triggerJSEvent("sessionEvents", "connectionDestroyed", data);
    }

    // signalListener
    public void onSignalReceived(Session arg0, String arg1, String arg2, Connection arg3) {
        JSONObject data = new JSONObject();
        Log.i(TAG, "signal type: " + arg1);
        Log.i(TAG, "signal data: " + arg2);
        try {
            data.put("type", arg1);
            data.put("data", arg2);
            if (arg3 != null) {
                data.put("connectionId", arg3.getConnectionId());
            }
            triggerJSEvent("sessionEvents", "signalReceived", data);
        } catch (JSONException e) {
        }
    }

    // streamPropertiesListener
    @Override
    public void onStreamHasAudioChanged(Session arg0, Stream arg1, boolean arg2) {
        // TODO Auto-generated method stub
        Log.i(TAG, "Stream audio changed: " + arg2);
    }

    @Override
    public void onStreamHasVideoChanged(Session arg0, Stream arg1, boolean arg2) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onStreamVideoDimensionsChanged(Session arg0, Stream arg1,
                                               int arg2, int arg3) {
        // TODO Auto-generated method stub

    }


    // Helper Methods
    public boolean compareStrings(String a, String b) {
        return (a != null && b != null && a.equalsIgnoreCase(b));
    }

    public void triggerStreamDestroyed(Stream arg1, String eventType) {
        JSONObject data = new JSONObject();
        try {
            JSONObject stream = createDataFromStream(arg1);
            data.put("stream", stream);
            triggerJSEvent("sessionEvents", "streamDestroyed", data);
        } catch (JSONException e) {
        }
    }

    public void triggerStreamCreated(Stream arg1, String eventType) {
        JSONObject data = new JSONObject();
        try {
            JSONObject stream = createDataFromStream(arg1);
            data.put("stream", stream);
            triggerJSEvent(eventType, "streamCreated", data);
        } catch (JSONException e) {
        }

        Log.i(TAG, "stream received done");
    }

    public JSONObject createDataFromConnection(Connection arg1) {
        JSONObject connection = new JSONObject();

        try {
            connection.put("connectionId", arg1.getConnectionId());
            connection.put("creationTime", arg1.getCreationTime());
            connection.put("data", arg1.getData());
        } catch (JSONException e) {
        }
        return connection;
    }

    public JSONObject createDataFromStream(Stream arg1) {
        JSONObject stream = new JSONObject();
        try {
            Connection connection = arg1.getConnection();
            if (connection != null) {
                stream.put("connectionId", connection.getConnectionId());
            }
            stream.put("creationTime", arg1.getCreationTime());
            stream.put("fps", -999);
            stream.put("hasAudio", arg1.hasAudio());
            stream.put("hasVideo", arg1.hasVideo());
            stream.put("name", arg1.getName());
            stream.put("streamId", arg1.getStreamId());
        } catch (Exception e) {
        }
        return stream;
    }

    public void triggerJSEvent(String event, String type, JSONObject data) {
        JSONObject message = new JSONObject();

        try {
            message.put("eventType", type);
            message.put("data", data);
        } catch (JSONException e) {
        }

        PluginResult myResult = new PluginResult(PluginResult.Status.OK, message);
        myResult.setKeepCallback(true);
        myEventListeners.get(event).sendPluginResult(myResult);
    }

    @Override
    public void onError(PublisherKit arg0, OpentokError arg1) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onStreamCreated(PublisherKit arg0, Stream arg1) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onStreamDestroyed(PublisherKit arg0, Stream arg1) {
        if (myPublisher != null) {
            myPublisher.destroyPublisher();
            myPublisher = null;
        }
    }

    @Override
    public void onStreamVideoTypeChanged(Session arg0, Stream arg1,
                                         StreamVideoType arg2) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onCameraChanged(Publisher publisher, int newCameraId) {
        if (newCameraId == 0) {
            mCamera = "front";
        } else {
            mCamera = "back";
        }
    }

    @Override
    public void onCameraError(Publisher publisher, OpentokError error) {
        Log.e(TAG, "session exception: " + error.getMessage());
    }
}
