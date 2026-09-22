package com.iljal.grblcontoller.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import com.iljal.grblcontoller.R;
import com.iljal.grblcontoller.events.GrblRealTimeCommandEvent;
import com.iljal.grblcontoller.helpers.NotificationHelper;
import com.iljal.grblcontoller.model.Constants;
import com.iljal.grblcontoller.model.GcodeCommand;
import com.iljal.grblcontoller.listeners.SerialTcpCommunicationHandler;

public class GrblTcpSerialService extends Service {
    private static final String TAG = "GrblTcpSerialService";
    private static final int NOTIFICATION_ID = 103;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final int STATE_NONE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private final IBinder binder = new TcpSerialBinder();
    private final Object ioLock = new Object();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private volatile int state = STATE_NONE;
    private volatile boolean stopping;
    private volatile Socket socket;
    private volatile OutputStream output;
    private Handler messageHandler;
    private SerialTcpCommunicationHandler serialHandler;
    private long statusUpdatePoolInterval = Constants.GRBL_STATUS_UPDATE_INTERVAL;

    public static volatile boolean isGrblFound;

    @Override
    public void onCreate() {
        super.onCreate();
        new NotificationHelper(this).createChannels();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, getNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, getNotification());
            }
        } catch (SecurityException e) {
            // Missing FOREGROUND_SERVICE_DATA_SYNC permission or background-start restriction
            Log.w(TAG, "Unable to start TCP service in foreground", e);
            stopSelf();
            return;
        }
        serialHandler = new SerialTcpCommunicationHandler(this);
        EventBus.getDefault().register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String host = intent == null ? "10.0.0.2" : intent.getStringExtra(EXTRA_HOST);
        int port = intent == null ? 12345 : intent.getIntExtra(EXTRA_PORT, 12345);
        if (host == null || host.trim().isEmpty()) host = "10.0.0.2";
        if (state == STATE_NONE) connect(host.trim(), port);
        return START_NOT_STICKY;
    }

    private void connect(String host, int port) {
        stopping = false;
        state = STATE_CONNECTING;
        sendState();
        new Thread(() -> {
            try {
                Socket connectedSocket = new Socket();
                connectedSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket = connectedSocket;
                output = connectedSocket.getOutputStream();
                state = STATE_CONNECTED;
                sendState();
                readLines(connectedSocket);
            } catch (IOException e) {
                Log.e(TAG, "TCP connection failed", e);
                sendToast(getString(R.string.text_unable_to_connect_to_device) + ": " + e.getMessage());
            } finally {
                closeConnection();
                if (!stopping) {
                    state = STATE_NONE;
                    sendState();
                }
            }
        }, "grbl-tcp-connection").start();
    }

    private void readLines(Socket connectedSocket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connectedSocket.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while (!stopping && state == STATE_CONNECTED && (line = reader.readLine()) != null) {
            serialHandler.obtainMessage(Constants.MESSAGE_READ, line.length(), -1, line).sendToTarget();
        }
    }

    public void serialWriteString(String command) {
        if (command == null) return;
        submitWrite((command + "\n").getBytes(StandardCharsets.UTF_8), command);
    }

    public void serialWriteByte(byte command) {
        submitWrite(new byte[]{command}, null);
    }

    private void submitWrite(byte[] data, String command) {
        try {
            writeExecutor.execute(() -> {
                synchronized (ioLock) {
                    if (output == null || state != STATE_CONNECTED) return;
                    try {
                        output.write(data);
                        output.flush();
                        if (command != null) {
                            serialHandler.obtainMessage(Constants.MESSAGE_WRITE, command.length(), -1, command).sendToTarget();
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "TCP write failed", e);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    public void setMessageHandler(Handler handler) {
        messageHandler = handler;
    }

    public int getState() {
        return state;
    }

    public long getStatusUpdatePoolInterval() {
        return statusUpdatePoolInterval;
    }

    public void setStatusUpdatePoolInterval(long interval) {
        statusUpdatePoolInterval = interval;
    }

    public void disconnectService() {
        stopping = true;
        state = STATE_NONE;
        closeConnection();
        serialHandler.stopGrblStatusUpdateService();
        sendState();
    }

    private void sendState() {
        Handler handler = messageHandler;
        if (handler != null) handler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    private void sendToast(String message) {
        Handler handler = messageHandler;
        if (handler == null) return;
        Message toast = handler.obtainMessage(Constants.MESSAGE_TOAST);
        toast.getData().putString(Constants.TOAST, message);
        toast.sendToTarget();
    }

    private void closeConnection() {
        synchronized (ioLock) {
            try {
                if (output != null) output.close();
            } catch (IOException ignored) {
            }
            output = null;
            Socket current = socket;
            socket = null;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SERVICE_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.text_tcp_service_foreground_message))
                .setSmallIcon(R.drawable.ic_stat_ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setOngoing(true)
                .build();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onGrblGcodeSendEvent(GcodeCommand event) {
        serialWriteString(event.getCommandString());
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onGrblRealTimeCommandEvent(GrblRealTimeCommandEvent event) {
        serialWriteByte(event.getCommand());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        disconnectService();
        writeExecutor.shutdownNow();
        EventBus.getDefault().unregister(this);
        stopForeground(true);
        super.onDestroy();
    }

    public class TcpSerialBinder extends Binder {
        public GrblTcpSerialService getService() {
            return GrblTcpSerialService.this;
        }
    }
}
