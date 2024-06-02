package de.timo.pilon_app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;

public class ControlFragment extends Fragment implements ServiceConnection, SerialListener {
    private enum Connected { False, Pending, True }
    private String deviceAddress;
    private SerialService service;
    public static Connected connected = Connected.False;
    private boolean initialStart = true;
    public static int current_pilon = 1;
    static Button send2btn;
    View disableAlarm;
    private ArrayAdapter<Integer> listAdapter;
    TextView connectedState;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);

        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False) disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null) {
            service.attach(this);
        } else getActivity().startService(new Intent(getActivity(), SerialService.class));
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations()) service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);

        send2btn = view.findViewById(R.id.activate);
        send2btn.setOnClickListener(v -> {
            if(connected == Connected.True) {

                NotificationManager mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE );
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !mNotificationManager.isNotificationPolicyAccessGranted()) {//Get DND Rechte um scharf zu schalten
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    getContext().getApplicationContext().startActivity(intent);
                } else {
                    send2btn.setEnabled(false);
                    int temp = current_pilon;
                    new Handler().postDelayed(() -> {
                        if(send2btn.getText() != "Nicht verbunden" && current_pilon == temp) {
                            send2btn.setEnabled(true);
                        }
                    },5000);
                    if(SerialSocket.active_pilons.get(current_pilon - 1) == 1 || SerialSocket.active_pilons.get(current_pilon - 1) == 3) { //Online bzw Online mit Funkproblem
                        send("!" + current_pilon + "|an");
                    } else if(SerialSocket.active_pilons.get(current_pilon - 1) == 2 || SerialSocket.active_pilons.get(current_pilon - 1) == 4) { //Scharf bzw Scharf mit Funkproblem
                        send("!" + current_pilon + "|aus");
                    }
                }
            }
        });

        disableAlarm = view.findViewById(R.id.stopAlarm);
        disableAlarm.setOnClickListener(v -> {
            if(connected == Connected.True) {
                NotificationManager nMgr = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
                nMgr.cancel(1212121212);
                disableAlarm.setEnabled(false);
                new Handler().postDelayed(() -> disableAlarm.setEnabled(true),5000);
                send("!0|aus");
            }
        });

        connectedState = view.findViewById(R.id.connectedState);

        ListView pilone = view.findViewById(R.id.pilone);
        listAdapter = new ArrayAdapter<Integer>(getActivity(), 0, SerialSocket.active_pilons) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                Integer state = SerialSocket.active_pilons.get(position);
                if (view == null) {
                    view = getActivity().getLayoutInflater().inflate(R.layout.pilon_item, parent, false);
                }

                ImageView itemImage = view.findViewById(R.id.imageView);
                TextView programTitle = view.findViewById(R.id.textView1);
                TextView programDescription = view.findViewById(R.id.textView2);
                ImageButton imageButton = view.findViewById(R.id.imageButton);

                String status;
                String scharf;
                if (state == 0) {
                    status = "stabil";
                    return new View(getContext());
                } else if (state == 1) {
                    itemImage.setImageResource(R.drawable.online);
                    status = "stabil";
                    scharf = "Nein";
                } else if (state == 2) {
                    itemImage.setImageResource(R.drawable.scharf);
                    status = "stabil";
                    scharf = "Ja";
                } else if (state == 3) {
                    itemImage.setImageResource(R.drawable.problem);
                    status = "instabil";
                    scharf = "Nein";
                } else if (state == 4) {
                    itemImage.setImageResource(R.drawable.scharf_problem);
                    status = "instabil";
                    scharf = "Ja";
                } else {
                    status = "stabil";
                    scharf = "Nein";
                }
                programTitle.setText("Pilon " + (position + 1));//programName[position]
                programDescription.setText("Beschreibung Pilon " + (position + 1));//programDescription[position]
                imageButton.setOnClickListener(v -> {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                    builder.setTitle("Eigenschaften Pilon " + (position + 1) + ":");
                    builder.setMessage("Name: Pilon " + (position + 1) + "\nStatus: Verbunden" + "\nVerbindung: " + status + "\nScharf geschalten?: " + scharf);
                    builder.setPositiveButton("OK", (dialog, id) -> {
                        Log.d("Dialog", "Ok");
                    });
                    AlertDialog dialog = builder.create();
                    dialog.show();
                });

                view.setOnClickListener(v -> {
                    current_pilon = position + 1;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (SerialSocket.active_pilons.get(position) == 1 || SerialSocket.active_pilons.get(position) == 3) {
                            send2btn.setText(R.string.scharf_schalten);
                            send2btn.setEnabled(true);
                        } else if (SerialSocket.active_pilons.get(position) == 2 || SerialSocket.active_pilons.get(position) == 4) {
                            send2btn.setText(R.string.entscharfen);
                            send2btn.setEnabled(true);
                        } else if (SerialSocket.active_pilons.get(position) == 0) {
                            send2btn.setText(R.string.not_connected);
                            send2btn.setEnabled(false);
                        }
                    }
                });
                return view;
            }
        };
        pilone.setAdapter(listAdapter);

        return view;
    }

    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        connectedState.setText(R.string.offline);
        connectedState.setBackgroundColor(Color.parseColor("#EC3C3C"));
        connectedState.setTextColor(Color.parseColor("#3E2723"));
        send2btn.setEnabled(false);
        disableAlarm.setEnabled(false);
        for (int activePilon : SerialSocket.active_pilons) {
            if (activePilon == 2 || activePilon == 4) {
                Log.d("sdcfdsaf", "Scharf geschaltenen Pilone alle offline");
            }
        }
        SerialSocket.active_pilons.clear();
        refresh();
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "nicht verbunden", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data;
            data = str.getBytes();
            service.write(data); //Sende Daten
            Log.d("SerialSocket", "Sende: " + str);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void receive(ArrayDeque<byte[]> datas) {//nur zur Anzeige im Terminal
        for (byte[] data : datas) {
            String msg = new String(data);
            Log.d("SerialSocket", "Empfange: " + msg);
            if(msg.contains("D:") || msg.contains("|an|OK") || msg.contains("|aus|OK")) {
                refresh();
            }
        }
    }

    public void refresh() {
        listAdapter.notifyDataSetChanged();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && SerialSocket.active_pilons.size() >= current_pilon) {
            if(SerialSocket.active_pilons.get(current_pilon - 1) == 1 || SerialSocket.active_pilons.get(current_pilon - 1) == 3 ) {
                send2btn.setText(R.string.scharf_schalten);
                send2btn.setEnabled(true);
            } else if(SerialSocket.active_pilons.get(current_pilon - 1) == 2 || SerialSocket.active_pilons.get(current_pilon - 1) == 4 ) {
                send2btn.setText(R.string.entscharfen);
                send2btn.setEnabled(true);
            } else if(SerialSocket.active_pilons.get(current_pilon - 1) == 0) {
                send2btn.setText(R.string.not_connected);
                send2btn.setEnabled(false);
            }
        }
    }

    @Override
    public void onSerialConnect() {
        connected = Connected.True;
        connectedState.setText(R.string.connected);
        connectedState.setBackgroundColor(Color.parseColor("#3EBC43"));
        connectedState.setTextColor(Color.parseColor("#3E2723"));
        disableAlarm.setEnabled(true);
        send("!0|devices");
    }

    @Override
    public void onSerialConnectError(Exception e) {
        Log.d("SerialSocket", "connection failed: " + e.getMessage());
        Toast.makeText(getContext(), "Verbindung fehlgeschlagen", Toast.LENGTH_SHORT).show();
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        Log.d("SerialSocket", "connection lost: " + e.getMessage());
        Toast.makeText(getContext(), "Verbindung abgebrochen", Toast.LENGTH_SHORT).show();
        disconnect();
    }

}
