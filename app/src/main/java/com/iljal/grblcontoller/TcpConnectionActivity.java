package com.iljal.grblcontoller;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.fonts.FontAwesomeIcons;

import androidx.appcompat.app.AlertDialog;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.iljal.grblcontoller.events.GrblSettingMessageEvent;
import com.iljal.grblcontoller.events.JogCommandEvent;
import com.iljal.grblcontoller.listeners.MachineStatusListener;
import com.iljal.grblcontoller.model.Constants;
import com.iljal.grblcontoller.service.FileStreamerIntentService;
import com.iljal.grblcontoller.service.GrblTcpSerialService;
import com.iljal.grblcontoller.util.GrblUtils;

public class TcpConnectionActivity extends GrblActivity {
    private static final String DEFAULT_HOST = "10.0.0.2";
    private static final int DEFAULT_PORT = 12345;

    private GrblTcpSerialService service;
    private boolean bound;
    private boolean readyToConnect;
    private final Handler messageHandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            if (message.what == Constants.MESSAGE_STATE_CHANGE) {
                updateConnectionState(message.arg1);
            } else if (message.what == Constants.MESSAGE_TOAST) {
                showToastMessage(message.getData().getString(Constants.TOAST), true, true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);
        boolean explicitEndpoint = getIntent().hasExtra(GrblTcpSerialService.EXTRA_HOST)
                || getIntent().hasExtra(GrblTcpSerialService.EXTRA_PORT);
        if (explicitEndpoint || (sharedPref.contains(getString(R.string.preference_tcp_host))
                && sharedPref.contains(getString(R.string.preference_tcp_port)))) {
            readyToConnect = true;
            startTcpConnection();
        } else {
            showConnectionDialog();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (readyToConnect && service == null) startTcpConnection();
    }

    private void startTcpConnection() {
        Intent intent = connectionIntent();
        if (!bound) bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) startForegroundService(intent);
        else startService(intent);
    }

    private void showConnectionDialog() {
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(padding, 0, padding, 0);

        EditText host = new EditText(this);
        host.setHint(R.string.text_tcp_host);
        host.setSingleLine(true);
        host.setText(sharedPref.getString(getString(R.string.preference_tcp_host), DEFAULT_HOST));

        EditText port = new EditText(this);
        port.setHint(R.string.text_tcp_port);
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(sharedPref.getString(getString(R.string.preference_tcp_port), String.valueOf(DEFAULT_PORT)));

        fields.addView(host, new LinearLayout.LayoutParams(-1, -2));
        fields.addView(port, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle(R.string.text_tcp_connection_title)
                .setView(fields)
                .setNegativeButton(R.string.text_cancel, (dialog, which) -> finish())
                .setPositiveButton(R.string.text_connect, (dialog, which) -> {
                    String hostValue = host.getText().toString().trim();
                    int portValue;
                    try {
                        portValue = Integer.parseInt(port.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        showToastMessage(getString(R.string.text_tcp_port) + ": 1-65535", true, true);
                        return;
                    }
                    if (hostValue.isEmpty() || portValue < 1 || portValue > 65535) {
                        showToastMessage(getString(R.string.text_tcp_host) + " / " + getString(R.string.text_tcp_port), true, true);
                        return;
                    }
                    sharedPref.edit()
                            .putString(getString(R.string.preference_tcp_host), hostValue)
                            .putString(getString(R.string.preference_tcp_port), String.valueOf(portValue))
                            .apply();
                    readyToConnect = true;
                    startTcpConnection();
                })
                .show();
    }

    private Intent connectionIntent() {
        String host = getIntent().getStringExtra(GrblTcpSerialService.EXTRA_HOST);
        if (host == null) host = sharedPref.getString(getString(R.string.preference_tcp_host), DEFAULT_HOST);
        int port = getIntent().getIntExtra(GrblTcpSerialService.EXTRA_PORT, -1);
        if (port < 1) {
            try {
                port = Integer.parseInt(sharedPref.getString(getString(R.string.preference_tcp_port), String.valueOf(DEFAULT_PORT)));
            } catch (NumberFormatException e) {
                port = DEFAULT_PORT;
            }
        }
        return new Intent(this, GrblTcpSerialService.class)
                .putExtra(GrblTcpSerialService.EXTRA_HOST, host)
                .putExtra(GrblTcpSerialService.EXTRA_PORT, port);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem connect = menu.findItem(R.id.action_connect);
        connect.setIcon(new IconDrawable(this, FontAwesomeIcons.fa_link).colorRes(R.color.colorWhite).sizeDp(24));
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_connect) {
            if (service != null && service.getState() == GrblTcpSerialService.STATE_CONNECTED) {
                service.disconnectService();
            } else {
                showConnectionDialog();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(int state) {
        if (getSupportActionBar() == null) return;
        if (state == GrblTcpSerialService.STATE_CONNECTING) {
            getSupportActionBar().setSubtitle(getString(R.string.text_connecting));
        } else if (state == GrblTcpSerialService.STATE_CONNECTED) {
            getSupportActionBar().setSubtitle(getString(R.string.text_connected));
            invalidateOptionsMenu();
        } else {
            getSupportActionBar().setSubtitle(getString(R.string.text_not_connected));
            MachineStatusListener.getInstance().setState(Constants.MACHINE_STATUS_NOT_CONNECTED);
            invalidateOptionsMenu();
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((GrblTcpSerialService.TcpSerialBinder) binder).getService();
            bound = true;
            service.setMessageHandler(messageHandler);
            service.setStatusUpdatePoolInterval(Long.parseLong(sharedPref.getString(
                    getString(R.string.preference_update_pool_interval),
                    String.valueOf(Constants.GRBL_STATUS_UPDATE_INTERVAL))));
            updateConnectionState(service.getState());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            updateConnectionState(GrblTcpSerialService.STATE_NONE);
        }
    };

    @Override
    public void onGcodeCommandReceived(String command) {
        if (service != null) service.serialWriteString(command);
    }

    @Override
    public void onGrblRealTimeCommandReceived(byte command) {
        if (service != null) service.serialWriteByte(command);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onJogCommandEvent(JogCommandEvent event) {
        if ((machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)
                || machineStatus.getState().equals(Constants.MACHINE_STATUS_JOG))
                && machineStatus.getPlannerBuffer() > 5) {
            onGcodeCommandReceived(event.getCommand());
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGrblSettingMessageEvent(GrblSettingMessageEvent event) {
        if (event.getSetting().equals("$10") && !event.getValue().equals("2")) {
            onGcodeCommandReceived("$10=2");
        }
    }

    @Override
    public void onDestroy() {
        if (bound) {
            service.setMessageHandler(null);
            unbindService(serviceConnection);
            bound = false;
        }
        stopService(new Intent(this, GrblTcpSerialService.class));
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
