package de.timo.pilon_app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@SuppressLint("MissingPermission") // various BluetoothGatt, BluetoothDevice methods
class SerialSocket extends BluetoothGattCallback {

    private static class DeviceDelegate {
        boolean connectCharacteristics(BluetoothGattService s) { return true; }
    }

    private static final UUID BLUETOOTH_LE_CCCD           = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID BLUETOOTH_LE_CC254X_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID BLUETOOTH_LE_CC254X_CHAR_RW = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private static final int MAX_MTU = 512; // BLE standard does not limit, some BLE 4.2 devices support 251, various source say that Android has max 512
    private static final int DEFAULT_MTU = 23;
    private static final String TAG = "SerialSocket";

    private final ArrayList<byte[]> writeBuffer;
    private final IntentFilter pairingIntentFilter;
    private final BroadcastReceiver pairingBroadcastReceiver;
    private final BroadcastReceiver disconnectBroadcastReceiver;

    private final Context context;
    private SerialListener listener;
    private DeviceDelegate delegate;
    private BluetoothDevice device;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic readCharacteristic, writeCharacteristic;

    private boolean writePending;
    private boolean canceled;
    private boolean connected;
    private int payloadSize = DEFAULT_MTU-3;
    public static List<Integer> active_pilons = new ArrayList<>(); // 0=Offline, 1=Online, 2=Scharf, 3=Online,Funkprobleme, 4=Scharf,Funkprobleme

    SerialSocket(Context context, BluetoothDevice device) {
        if(context instanceof Activity)
            throw new InvalidParameterException("expected non UI context");
        this.context = context;
        this.device = device;
        writeBuffer = new ArrayList<>();
        pairingIntentFilter = new IntentFilter();
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onPairingBroadcastReceive(context, intent);
            }
        };
        disconnectBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(listener != null) listener.onSerialIoError(new IOException("background disconnect"));
                disconnect();
            }
        };
    }

    String getName() {
        return device.getName() != null ? device.getName() : device.getAddress();
    }

    void disconnect() {
        Log.d(TAG, "disconnect");
        listener = null;
        device = null;
        canceled = true;
        synchronized (writeBuffer) {
            writePending = false;
            writeBuffer.clear();
        }
        readCharacteristic = null;
        writeCharacteristic = null;
        for (int activePilon : active_pilons) {
            if (activePilon == 2 || activePilon == 4) {
                Log.d("sdcfdsaf", "Scharf geschaltenen Pilone alle offline");
            }
        }
        active_pilons.clear();
        if (gatt != null) {
            Log.d(TAG, "gatt.disconnect");
            gatt.disconnect();
            Log.d(TAG, "gatt.close");
            try {
                gatt.close();
            } catch (Exception ignored) {}
            gatt = null;
            connected = false;
        }
        try {
            context.unregisterReceiver(pairingBroadcastReceiver);
        } catch (Exception ignored) {
        }
        try {
            context.unregisterReceiver(disconnectBroadcastReceiver);
        } catch (Exception ignored) {
        }
    }

    void connect(SerialListener listener) throws IOException {
        if(connected || gatt != null)
            throw new IOException("already connected");
        canceled = false;
        this.listener = listener;
        ContextCompat.registerReceiver(context, disconnectBroadcastReceiver, new IntentFilter("de.timo.pilon_app.Disconnect"), ContextCompat.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, "connect "+device);
        context.registerReceiver(pairingBroadcastReceiver, pairingIntentFilter);
        if (Build.VERSION.SDK_INT < 23) {
            Log.d(TAG, "connectGatt");
            gatt = device.connectGatt(context, false, this);
        } else {
            Log.d(TAG, "connectGatt,LE");
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
        }
        if (gatt == null)
            throw new IOException("connectGatt failed");
    }

    private void onPairingBroadcastReceive(Context context, Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if(device==null || !device.equals(this.device)) return;
        switch (Objects.requireNonNull(intent.getAction())) {
            case BluetoothDevice.ACTION_PAIRING_REQUEST:
                final int pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                Log.d(TAG, "pairing request " + pairingVariant);
                onSerialConnectError(new IOException(context.getString(R.string.pairing_request)));
                break;
            case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                Log.d(TAG, "bond state " + previousBondState + "->" + bondState);
                break;
            default:
                Log.d(TAG, "unknown broadcast " + intent.getAction());
                break;
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG,"connect status "+status+", discoverServices");
            if (!gatt.discoverServices())
                onSerialConnectError(new IOException("discoverServices failed"));
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (connected)
                onSerialIoError     (new IOException("gatt status " + status));
            else
                onSerialConnectError(new IOException("gatt status " + status));
        } else {
            Log.d(TAG, "unknown connect state "+newState+" "+status);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        Log.d(TAG, "servicesDiscovered, status " + status);
        if (canceled) return;

        boolean sync = true; //connectCharacteristics1
        writePending = false;
        for (BluetoothGattService gattService : gatt.getServices()) {
            if (gattService.getUuid().equals(BLUETOOTH_LE_CC254X_SERVICE)) delegate = new Cc245XDelegate();

            if(delegate != null) {
                sync = delegate.connectCharacteristics(gattService);
                break;
            }
        }
        if(canceled) return;
        if(delegate==null || readCharacteristic==null || writeCharacteristic==null) {
            for (BluetoothGattService gattService : gatt.getServices()) {
                Log.d(TAG, "service "+gattService.getUuid());
                for(BluetoothGattCharacteristic characteristic : gattService.getCharacteristics())
                    Log.d(TAG, "characteristic "+characteristic.getUuid());
            }
            onSerialConnectError(new IOException("no serial profile found"));
            return;
        }
        if(sync) { //connectCharacteristics2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Log.d(TAG, "request max MTU");
                if (!gatt.requestMtu(MAX_MTU)) onSerialConnectError(new IOException("request MTU failed"));
            } else {
                connectCharacteristics3(gatt);
            }
        }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        Log.d(TAG,"mtu size "+mtu+", status="+status);
        if(status ==  BluetoothGatt.GATT_SUCCESS) {
            payloadSize = mtu - 3;
            Log.d(TAG, "payload size "+payloadSize);
        }
        connectCharacteristics3(gatt);
    }

    private void connectCharacteristics3(BluetoothGatt gatt) {
        int writeProperties = writeCharacteristic.getProperties();
        if((writeProperties & (BluetoothGattCharacteristic.PROPERTY_WRITE + BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) ==0) {
            onSerialConnectError(new IOException("write characteristic not writable"));
            return;
        }
        if(!gatt.setCharacteristicNotification(readCharacteristic,true)) {
            onSerialConnectError(new IOException("no notification for read characteristic"));
            return;
        }
        BluetoothGattDescriptor readDescriptor = readCharacteristic.getDescriptor(BLUETOOTH_LE_CCCD);
        if(readDescriptor == null) {
            onSerialConnectError(new IOException("no CCCD descriptor for read characteristic"));
            return;
        }
        int readProperties = readCharacteristic.getProperties();
        if((readProperties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            Log.d(TAG, "enable read indication");
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        }else if((readProperties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            Log.d(TAG, "enable read notification");
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            onSerialConnectError(new IOException("no indication/notification for read characteristic ("+readProperties+")"));
            return;
        }
        Log.d(TAG,"writing read characteristic descriptor");
        if(!gatt.writeDescriptor(readDescriptor)) {
            onSerialConnectError(new IOException("read characteristic CCCD descriptor not writable"));
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        if(canceled)
            return;
        if(descriptor.getCharacteristic() == readCharacteristic) {
            Log.d(TAG,"writing read characteristic descriptor finished, status="+status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onSerialConnectError(new IOException("write descriptor failed"));
            } else {
                onSerialConnect();
                connected = true;
                Log.d(TAG, "connected");
            }
        }
    }

    /*
     * read
     */
    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if(canceled) return;
        if(characteristic == readCharacteristic) {
            byte[] data = readCharacteristic.getValue();
            onSerialRead(data);
            Log.d(TAG,"read, len="+data.length);
        }
    }

    /*
     * write
     */
    void write(byte[] data) throws IOException {
        if(canceled || !connected || writeCharacteristic == null)
            throw new IOException("not connected");
        byte[] data0;
        synchronized (writeBuffer) {
            if(data.length <= payloadSize) {
                data0 = data;
            } else {
                data0 = Arrays.copyOfRange(data, 0, payloadSize);
            }
            if(!writePending && writeBuffer.isEmpty()) {
                writePending = true;
            } else {
                writeBuffer.add(data0);
                Log.d(TAG,"write queued, len="+data0.length);
                data0 = null;
            }
            if(data.length > payloadSize) {
                for(int i=1; i<(data.length+payloadSize-1)/payloadSize; i++) {
                    int from = i*payloadSize;
                    int to = Math.min(from+payloadSize, data.length);
                    writeBuffer.add(Arrays.copyOfRange(data, from, to));
                    Log.d(TAG,"write queued, len="+(to-from));
                }
            }
        }
        if(data0 != null) {
            writeCharacteristic.setValue(data0);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(new IOException("write failed"));
            } else {
                Log.d(TAG,"write started, len="+data0.length);
            }
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if(canceled || !connected || writeCharacteristic == null)
            return;
        if(status != BluetoothGatt.GATT_SUCCESS) {
            onSerialIoError(new IOException("write failed"));
            return;
        }
        if(characteristic == writeCharacteristic) { // NOPMD - test object identity
            Log.d(TAG,"write finished, status="+status);
            writeNext();
        }
    }

    private void writeNext() {
        final byte[] data;
        synchronized (writeBuffer) {
            if (!writeBuffer.isEmpty()) {
                writePending = true;
                data = writeBuffer.remove(0);
            } else {
                writePending = false;
                data = null;
            }
        }
        if(data != null) {
            writeCharacteristic.setValue(data);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(new IOException("write failed"));
            } else {
                Log.d(TAG,"write started, len="+data.length);
            }
        }
    }


    private void onSerialConnect() {
        if (listener != null)
            listener.onSerialConnect();
    }

    private void onSerialConnectError(Exception e) {
        canceled = true;
        if (listener != null)
            listener.onSerialConnectError(e);
    }

    private void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        for (byte[] dataa : datas) {
            String msg = new String(dataa);
            if(msg.equals("Alarm")) {
                send("!0|stop");
                Log.d("Pylon", "ALARM!");

                SharedPreferences sharedPreferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);
                boolean ton = sharedPreferences.getBoolean("Ton", false);
                if(ton) {
                    String path = sharedPreferences.getString("Path", "Standart Ton");
                    int SoundVersion = 0;
                    Uri sound = Uri.parse("android.resource://" + context.getApplicationContext().getPackageName() + "/" + R.raw.sound);
                    if(!path.equals("Standart Ton")) {
                        SoundVersion = sharedPreferences.getInt("SoundVersion", 1);
                        sound = Uri.parse(path);
                    }
                    Log.d("Version", String.valueOf(SoundVersion));

                    Intent stopAlarmIntent = new Intent()
                            .setClassName(context, "de.timo.pilon_app.MainActivity")
                            .setAction(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER);
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
                    PendingIntent stopAlarmPendingIntent = PendingIntent.getActivity(context, 1, stopAlarmIntent,  flags);

                    NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context.getApplicationContext(), "default_notification_channel_id" )
                            .setSmallIcon(R.drawable.ic_notification )
                            .setContentTitle("Warnung")
                            .setSound(sound)
                            .setContentIntent(stopAlarmPendingIntent)
                            .setDeleteIntent(stopAlarmPendingIntent)
                            .setContentText("Das Hütchen ist umgefallen!")
                            .setAutoCancel(true)
                            .setOngoing(true);
                    NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE );

                    AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, sharedPreferences.getInt("Volume", 5), 0); //MAX = 15

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !mNotificationManager.isNotificationPolicyAccessGranted()) {//Get DND Rechte
                        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        context.getApplicationContext().startActivity(intent);
                    } else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                        mNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                    }

                    if (Build.VERSION. SDK_INT >= Build.VERSION_CODES.O ) {
                        if(SoundVersion != 0 && mNotificationManager.getNotificationChannel("Alarm" + (SoundVersion-1)) != null) {
                            mNotificationManager.deleteNotificationChannel("Alarm" + (SoundVersion-1));
                            if(mNotificationManager.getNotificationChannel("Alarm0") != null) {
                                mNotificationManager.deleteNotificationChannel("Alarm0");
                            }
                        }
                        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN )
                                .setUsage(AudioAttributes.USAGE_ALARM )
                                .build() ;
                        int importance = NotificationManager. IMPORTANCE_HIGH ;
                        NotificationChannel notificationChannel = new NotificationChannel( "Alarm" + SoundVersion , "Alarm" , importance) ;
                        notificationChannel.enableLights( true ) ;
                        notificationChannel.setLightColor(Color.RED ) ;
                        notificationChannel.enableVibration( true ) ;
                        notificationChannel.setVibrationPattern( new long []{ 100 , 200 , 300 , 400 , 500 , 400 , 300 , 200 , 400 }) ;
                        notificationChannel.setSound(sound, audioAttributes) ;
                        mBuilder.setChannelId( "Alarm" + SoundVersion ) ;
                        mNotificationManager.createNotificationChannel(notificationChannel) ;
                    }
                    assert mNotificationManager != null;
                    mNotificationManager.notify(1212121212, mBuilder.build());
                } else {
                    Toast.makeText(context, "Alarm!!!", Toast.LENGTH_LONG).show();// Alarm
                }


            } else if(msg.contains("D:")) {
                String[] numbers = msg.split(":");
                if(numbers.length-1 == active_pilons.size()) {
                    for(int i = 1; i<numbers.length; i++) {
                        if(active_pilons.get(i - 1) == 2 && Integer.parseInt(numbers[i]) == 0) {
                            Toast.makeText(context, "scharf geschaltenes Pylon offline", Toast.LENGTH_SHORT).show(); //Alarm
                        } else if(active_pilons.get(i - 1) == 4 && Integer.parseInt(numbers[i]) == 0) {
                            Toast.makeText(context, "scharf geschaltenes Pylon mit Problem jetzt offline", Toast.LENGTH_SHORT).show();// Alarm
                        } else if(active_pilons.get(i - 1) == 2 && Integer.parseInt(numbers[i]) == 4) {
                            Toast.makeText(context, "scharf geschaltenes Pylon hat jetzt Verbindungsprobleme", Toast.LENGTH_SHORT).show(); //Info
                        }
                    }
                }

                active_pilons.clear();
                Integer[] tempArray = new Integer[numbers.length-1];
                for(int i = 0; i<numbers.length-1; i++) {
                    tempArray[i] = Integer.valueOf(numbers[i+1]);
                }
                active_pilons.addAll(Arrays.asList(tempArray));
            } else if(msg.contains("|an|OK|P")) {
                active_pilons.set(Integer.parseInt(msg.split("\\|")[0]) - 1, 4); // -> |
            } else if(msg.contains("|aus|OK|P")) {
                active_pilons.set(Integer.parseInt(msg.split("\\|")[0]) - 1, 3); // -> |
            } else if(msg.contains("|an|OK")) {
                active_pilons.set(Integer.parseInt(msg.split("\\|")[0]) - 1, 2); // -> |
            } else if(msg.contains("|aus|OK")) {
                active_pilons.set(Integer.parseInt(msg.split("\\|")[0]) - 1, 1); // -> |
            } else if(msg.contains("|off|OK")) {
                active_pilons.set(Integer.parseInt(msg.split("\\|")[0]) - 1, 1); // -> |
                NotificationManager nMgr = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                nMgr.cancel(1212121212);
            }
        }


        if (listener != null) listener.onSerialRead(data);

    }

    private void send(String str) {
        if(!connected) {
            Toast.makeText(context.getApplicationContext(), "nicht verbunden", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data;
            data = str.getBytes();
            write(data); //Sende Daten
            Log.d("SerialSocket", "Sende: " + str);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void onSerialIoError(Exception e) {
        writePending = false;
        canceled = true;
        if (listener != null)
            listener.onSerialIoError(e);
    }


    private class Cc245XDelegate extends DeviceDelegate {
        @Override
        boolean connectCharacteristics(BluetoothGattService gattService) {
            Log.d(TAG, "service cc254x uart");
            readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW);
            writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW);
            return true;
        }
    }

}
