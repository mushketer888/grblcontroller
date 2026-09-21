package com.iljal.grblcontoller.listeners;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import org.greenrobot.eventbus.EventBus;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.iljal.grblcontoller.events.ConsoleMessageEvent;
import com.iljal.grblcontoller.model.Constants;
import com.iljal.grblcontoller.service.GrblTcpSerialService;
import com.iljal.grblcontoller.util.GrblUtils;

public class SerialTcpCommunicationHandler extends SerialCommunicationHandler {
    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
    private final WeakReference<GrblTcpSerialService> service;
    private ScheduledExecutorService statusUpdater;

    public SerialTcpCommunicationHandler(GrblTcpSerialService service) {
        this.service = new WeakReference<>(service);
    }

    @Override
    public void handleMessage(Message message) {
        GrblTcpSerialService tcpService = service.get();
        if (tcpService == null) return;
        if (message.what == Constants.MESSAGE_READ && message.arg1 > 0) {
            String line = (String) message.obj;
            if (!singleThreadExecutor.isShutdown()) {
                singleThreadExecutor.submit(() -> onTcpSerialRead(line.trim(), tcpService));
            }
        } else if (message.what == Constants.MESSAGE_WRITE) {
            EventBus.getDefault().post(new ConsoleMessageEvent((String) message.obj));
        }
    }

    private void onTcpSerialRead(String message, GrblTcpSerialService service) {
        if (!onSerialRead(message)) return;
        GrblTcpSerialService.isGrblFound = true;
        Handler handler = new Handler(Looper.getMainLooper());
        long delay = service.getStatusUpdatePoolInterval();
        for (String command : getStartUpCommands()) {
            handler.postDelayed(() -> service.serialWriteString(command), delay);
            delay += service.getStatusUpdatePoolInterval();
        }
        stopGrblStatusUpdateService();
        statusUpdater = Executors.newSingleThreadScheduledExecutor();
        statusUpdater.scheduleWithFixedDelay(
                () -> service.serialWriteByte(GrblUtils.GRBL_STATUS_COMMAND),
                service.getStatusUpdatePoolInterval(),
                service.getStatusUpdatePoolInterval(),
                TimeUnit.MILLISECONDS);
    }

    public void stopGrblStatusUpdateService() {
        if (statusUpdater != null) statusUpdater.shutdownNow();
    }
}
