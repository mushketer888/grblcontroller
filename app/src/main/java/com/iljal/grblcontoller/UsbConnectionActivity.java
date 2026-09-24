/*
 *
 *  *  /**
 *  *  * Copyright (C) 2017  Grbl Controller Contributors
 *  *  *
 *  *  * This program is free software; you can redistribute it and/or modify
 *  *  * it under the terms of the GNU General Public License as published by
 *  *  * the Free Software Foundation; either version 2 of the License, or
 *  *  * (at your option) any later version.
 *  *  *
 *  *  * This program is distributed in the hope that it will be useful,
 *  *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *  * GNU General Public License for more details.
 *  *  *
 *  *  * You should have received a copy of the GNU General Public License along
 *  *  * with this program; if not, write to the Free Software Foundation, Inc.,
 *  *  * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *  *  * <http://www.gnu.org/licenses/>
 *  *
 *
 */

package com.iljal.grblcontoller;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.Menu;
import android.view.MenuItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.fonts.FontAwesomeIcons;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.iljal.grblcontoller.events.GrblSettingMessageEvent;
import com.iljal.grblcontoller.events.JogCommandEvent;
import com.iljal.grblcontoller.listeners.MachineStatusListener;
import com.iljal.grblcontoller.model.Constants;
import com.iljal.grblcontoller.service.FileStreamerIntentService;
import com.iljal.grblcontoller.service.GrblUsbSerialService;
import com.iljal.grblcontoller.util.GrblUtils;

public class UsbConnectionActivity extends GrblActivity{

    private GrblUsbSerialService grblUsbSerialService;
    private GrblServiceMessageHandler grblServiceMessageHandler;
    private boolean mBound = false;
    private boolean usbStartRequested = false;
    private boolean usbSelectionPrompted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        grblServiceMessageHandler = new GrblServiceMessageHandler();

        Intent intent = new Intent(getApplicationContext(), GrblUsbSerialService.class);
        bindService(intent, usbConnection, Context.BIND_AUTO_CREATE);

        EventBus.getDefault().register(this);
    }

    @Override
    public void onStart(){
        super.onStart();
        setFilters();  // Start listening notifications from UsbService

        if (!usbStartRequested) selectUsbDeviceAndStart();
    }

    private void selectUsbDeviceAndStart() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> devices = usbManager == null ? new HashMap<>() : usbManager.getDeviceList();
        List<UsbDevice> serialDevices = new ArrayList<>();
        for (UsbDevice candidate : devices.values()) {
            int vendorId = candidate.getVendorId();
            int productId = candidate.getProductId();
            boolean rootHub = vendorId == 0x1d6b && (productId == 0x0001 || productId == 0x0002 || productId == 0x0003);
            boolean excludedHub = vendorId == 0x5c6 && productId == 0x904c;
            if (!rootHub && !excludedHub) serialDevices.add(candidate);
        }

        if (serialDevices.size() <= 1) {
            String deviceName = serialDevices.isEmpty() ? null : serialDevices.get(0).getDeviceName();
            if (deviceName != null) {
                sharedPref.edit().putString(getString(R.string.preference_usb_device_name), deviceName).apply();
            }
            startUsbService(deviceName);
            return;
        }
        if (usbSelectionPrompted) return;
        usbSelectionPrompted = true;

        String[] labels = new String[serialDevices.size()];
        String selectedName = sharedPref.getString(getString(R.string.preference_usb_device_name), null);
        int[] selectedIndex = {0};
        for (int i = 0; i < serialDevices.size(); i++) {
            UsbDevice candidate = serialDevices.get(i);
            String product = candidate.getProductName();
            labels[i] = candidate.getDeviceName() + "  VID " + candidate.getVendorId()
                    + " / PID " + candidate.getProductId()
                    + (product == null ? "" : "  " + product);
            if (candidate.getDeviceName().equals(selectedName)) selectedIndex[0] = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Select USB device")
                .setSingleChoiceItems(labels, selectedIndex[0], (dialog, which) -> selectedIndex[0] = which)
                .setNegativeButton(R.string.text_cancel, (dialog, which) -> finish())
                .setPositiveButton(R.string.text_connect, (dialog, which) -> {
                    String deviceName = serialDevices.get(selectedIndex[0]).getDeviceName();
                    sharedPref.edit().putString(getString(R.string.preference_usb_device_name), deviceName).apply();
                    startUsbService(deviceName);
                })
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void startUsbService(String deviceName) {
        usbStartRequested = true;
        Intent intent = new Intent(getApplicationContext(), GrblUsbSerialService.class);
        if (deviceName != null) intent.putExtra(GrblUsbSerialService.EXTRA_USB_DEVICE_NAME, deviceName);
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) getApplicationContext().startForegroundService(intent);
        else startService(intent);
    }

    @Override
    public void onDestroy() {
        if (mBound && grblUsbSerialService != null) onGcodeCommandReceived("$10=1");
        unregisterReceiver(mUsbReceiver);
        if(mBound){
            grblUsbSerialService.setMessageHandler(null);
            unbindService(usbConnection);
            mBound = false;
        }
        stopService(new Intent(this, GrblUsbSerialService.class));
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        MenuItem actionConnect = menu.findItem(R.id.action_connect);
        actionConnect.setIcon(new IconDrawable(this, FontAwesomeIcons.fa_usb).colorRes(R.color.colorWhite).sizeDp(24));
        return super.onCreateOptionsMenu(menu);
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id){

            case R.id.action_connect:
                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this)
                        .setTitle("USB OTG Connection")
                        .setMessage("To connect or disconnect a device, just plug or unplug the usb cable.")
                        .setPositiveButton(getString(R.string.text_ok), (dialog, which) -> { })
                        .setCancelable(false);

                alertDialogBuilder.show();
                break;

            case R.id.action_grbl_reset:
                boolean resetConfirm = sharedPref.getBoolean(getString(R.string.preference_confirm_grbl_soft_reset), true);
                if(resetConfirm){
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.text_grbl_soft_reset)
                            .setMessage(R.string.text_grbl_soft_reset_desc)
                            .setPositiveButton(getString(R.string.text_yes_confirm), (dialog, which) -> {
                                if(FileStreamerIntentService.getIsServiceRunning()){
                                    FileStreamerIntentService.setShouldContinue(false);
                                    Intent intent = new Intent(getApplicationContext(), FileStreamerIntentService.class);
                                    stopService(intent);
                                }
                                onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESET_COMMAND);
                            })
                            .setNegativeButton(getString(R.string.text_cancel), null)
                            .show();

                }else{
                    onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESET_COMMAND);
                }
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(GrblUsbSerialService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(GrblUsbSerialService.ACTION_NO_USB);
        filter.addAction(GrblUsbSerialService.ACTION_USB_DISCONNECTED);
        filter.addAction(GrblUsbSerialService.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(GrblUsbSerialService.ACTION_USB_PERMISSION_NOT_GRANTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mUsbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mUsbReceiver, filter);
        }
    }

    /*
     * This handler will be passed to UsbService. Data received from serial port is displayed through this handler
     */
    private static class GrblServiceMessageHandler extends Handler {

        public GrblServiceMessageHandler() {
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case GrblUsbSerialService.MESSAGE_FROM_SERIAL_PORT:
                case GrblUsbSerialService.CTS_CHANGE:
                case GrblUsbSerialService.DSR_CHANGE:
                    break;
            }
        }
    }

    /*
     * Notifications from UsbService will be received here.
     */
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String intentAction = intent.getAction();
            if(intentAction == null){
                showToastMessage("Unknown error", true, true);
                return;
            }

            switch (intentAction) {
                case GrblUsbSerialService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    if(getSupportActionBar() != null) getSupportActionBar().setSubtitle(getString(R.string.text_connected));
                    showToastMessage(getString(R.string.text_usb_device_connected));
                    break;
                case GrblUsbSerialService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    if(getSupportActionBar() != null) getSupportActionBar().setSubtitle(R.string.text_no_usb_permission);
                    showToastMessage(getString(R.string.text_usb_permission_not_granted), true, true);
                    break;
                case GrblUsbSerialService.ACTION_NO_USB: // NO USB CONNECTED
                    if(getSupportActionBar() != null) getSupportActionBar().setSubtitle(R.string.text_no_usb_device);
                    //grblToast("USB device not connected");
                    break;
                case GrblUsbSerialService.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
                    if(getSupportActionBar() != null) getSupportActionBar().setSubtitle(getString(R.string.text_not_connected));
                    MachineStatusListener.getInstance().setState(Constants.MACHINE_STATUS_NOT_CONNECTED);
                    if(FileStreamerIntentService.getIsServiceRunning()){
                        FileStreamerIntentService.setShouldContinue(false);
                        stopService(new Intent(getApplicationContext(), FileStreamerIntentService.class));
                    }
                    showToastMessage(getString(R.string.text_usb_device_disconnected), true, true);
                    break;
                case GrblUsbSerialService.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
                    if(getSupportActionBar() != null) getSupportActionBar().setSubtitle(R.string.text_usb_device_not_supported);
                    MachineStatusListener.getInstance().setState(Constants.MACHINE_STATUS_NOT_CONNECTED);
                    showToastMessage(getString(R.string.text_usb_device_not_supported), true, true);
                    break;
            }
        }
    };

    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            grblUsbSerialService = ((GrblUsbSerialService.UsbSerialBinder) service).getService();
            mBound = true;
            grblUsbSerialService.setMessageHandler(grblServiceMessageHandler);
            grblUsbSerialService.setStatusUpdatePoolInterval(Long.parseLong(sharedPref.getString(getString(R.string.preference_update_pool_interval), String.valueOf(Constants.GRBL_STATUS_UPDATE_INTERVAL))));
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
            grblUsbSerialService = null;
        }
    };


    @Override
    public void onGcodeCommandReceived(String command) {
        if(grblUsbSerialService != null){
            grblUsbSerialService.serialWriteString(command);
        }
    }

    @Override
    public void onGrblRealTimeCommandReceived(byte command) {
        if(grblUsbSerialService != null) grblUsbSerialService.serialWriteByte(command);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onJogCommandEvent(JogCommandEvent event){
        if(machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE) || machineStatus.getState().equals(Constants.MACHINE_STATUS_JOG)){
            if(machineStatus.getPlannerBuffer() > 5) onGcodeCommandReceived(event.getCommand());
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGrblSettingMessageEvent(GrblSettingMessageEvent event){

        if(event.getSetting().equals("$10") && !event.getValue().equals("2")){
            onGcodeCommandReceived("$10=2");
        }

        if(event.getSetting().equals("$110") || event.getSetting().equals("$111") || event.getSetting().equals("$112")){
            double maxFeedRate = Double.parseDouble(event.getValue());
            if(maxFeedRate > sharedPref.getDouble(getString(R.string.preference_jogging_max_feed_rate), machineStatus.getJogging().feed)){
                sharedPref.edit().putDouble(getString(R.string.preference_jogging_max_feed_rate), maxFeedRate).apply();
            }
        }

        if(event.getSetting().equals("$32")){
            machineStatus.setLaserModeEnabled(event.getValue().equals("1"));
        }

    }


}
