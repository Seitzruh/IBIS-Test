package de.bconn.IBISTest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import de.bconn.IBISTest.driver.*;
import de.bconn.IBISTest.util.HexDump;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.Executors;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission {Unknown, Requested, Granted, Denied}

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;
    View view;

    static class dsItem {
        String description;
        String dscode;
        String definition;
        String example;

        dsItem(String dscode, String definition, String description, String example) {
            this.description = description;
            this.dscode = dscode;
            this.definition = definition;
            this.example = example;
        }

        public String toString() {
            return (dscode + "      ").substring(0, 7) + (definition + "           ").substring(0, 10) + description;
        }

    }

    ArrayList<dsItem> dsItems = new ArrayList<>();
    private ArrayAdapter<dsItem> arrayAdapter;
    private int deviceId, portNum;
    private boolean withIoManager;

    private BroadcastReceiver broadcastReceiver;
    private Handler mainLooper;
    private TextView receiveText;

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(INTENT_ACTION_GRANT_USB)) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        withIoManager = getArguments().getBoolean("withIoManager");
        if (dsItems.size() == 0) {
            String[] values = getResources().getStringArray(R.array.ds_array);
            for (String value : values) {
                String[] sp = value.split(";");
                String a = sp[0];
                if (sp.length >= 3)
                    dsItems.add(new dsItem(sp[0], sp[1], sp[3], sp[2]));
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));

        if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
            mainLooper.post(this::connect);
    }

    @Override
    public void onPause() {
        if (connected) {
            status("disconnected");
            disconnect();
        }
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        TextView sendText = view.findViewById(R.id.send_text);
        TextView txt1 = view.findViewById(R.id.txt1);
        TextView txt2 = view.findViewById(R.id.txt2);
        TextView addr = view.findViewById(R.id.addr);
        TextView stop = view.findViewById(R.id.stop);

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));

        View infoBtn = view.findViewById(R.id.info_btn);
        infoBtn.setOnClickListener(v -> show_ibiscodes());

        View datzeiBtn = view.findViewById(R.id.datzeibtn);
        datzeiBtn.setOnClickListener(v -> datzeitBtn());

        View ds09Btn = view.findViewById(R.id.ds09btn);
        ds09Btn.setOnClickListener(v -> send("v" + z16(txt1.getText().toString())));

        View ds10Btn = view.findViewById(R.id.ds10btn);
        ds10Btn.setOnClickListener(v -> ds10Btn(stop.getText().toString()));

        View ds21Btn = view.findViewById(R.id.ds21btn);
        ds21Btn.setOnClickListener(v -> {
            ds21Btn(addr.getText().toString(),txt1.getText().toString(), txt2.getText().toString());
        });

        View ds21aBtn = view.findViewById(R.id.ds21abtn);
        ds21aBtn.setOnClickListener(v -> {
            ds21aBtn(addr.getText().toString(), stop.getText().toString(), txt1.getText().toString(), txt2.getText().toString());
        });

        View ds03aBtn = view.findViewById(R.id.ds03abtn);
        ds03aBtn.setOnClickListener(v -> { send("zA" + sexteenstring(z16(txt1.getText().toString())+z16(txt2.getText().toString())));
        });

        View ds03cBtn = view.findViewById(R.id.ds03cbtn);
        ds03cBtn.setOnClickListener(v -> {
            send("zI" + fourstring(txt1.getText().toString()));
        });

        View ds04cBtn = view.findViewById(R.id.ds04cbtn);
        ds04cBtn.setOnClickListener(v -> {
            send("eT" + fourstring(txt1.getText().toString()));
        });

        View receiveBtn = view.findViewById(R.id.receive_btn);
        if (withIoManager) {
            receiveBtn.setVisibility(View.GONE);
        } else {
            receiveBtn.setOnClickListener(v -> read());
        }
        return view;
    }


    private String sexteenstring(String text1) {
        int len = text1.length() / 16;
        if (text1.length() % 16 > 0) len += 1;
        String hexString = int2hexString(len, 1);
        return hexString + (text1 + "                ").substring(0, 16 * len);
    }

    private String fourstring(String text1) {
        int len = text1.length() / 4;
        if (text1.length() % 4 > 0) len += 1;
        String hexString = int2hexString(len, 1);
        return hexString + (text1 + "   ").substring(0, 4 * len);
    }


    private void ds21Btn(String adresse, String txt1, String txt2) {
        //ds21
        send("aA"+adresse +sexteenstring(z16(txt1) + z16(txt2)));
        send("aA"+adresse +sexteenstring( z16("Halt2") + z16("Text2")));
    }

    private void ds21aBtn(String adresse, String stoptxt, String txt1, String txt2) {
        int stop = Integer.parseInt(stoptxt);
        // ds21a
        //sende 99
        send("aL" + adresse + "09" + (char) 3 + "99");
        send("aL" + ds21String(adresse, stop, txt1, txt2));
        send("aL" + ds21String(adresse, stop + 1, "zweiter Halt", "Umstieg Line 03"));
        send("aL" + ds21String(adresse, stop + 2, "2 HST", "Text 3"));
        send("aL" + adresse + "09" + (char) 3 + "99");
    }

    private String parseStop(String stopTxt) {
        int stop = 0;

        try {
            stop = Integer.parseInt(stopTxt);
        } catch (NumberFormatException nfe) {
            System.out.println("Could not parse " + nfe);
        }

        String strZiel = Integer.toString(stop);

        if (stop > 99 || stop == 0) strZiel = "01";
        if (strZiel.length() == 1) strZiel = "0" + strZiel;

        return strZiel;
    }

    private String z16(String txt) {
        return (txt + "                    ").substring(0, 15);
    }

    private String ds21String(String adresse, int stop, String text1, String text2) {
        String retString;
        if (adresse.length() != 1) adresse = "1";
        String stoptxt = parseStop(Integer.toString(stop));
        retString = (char) 3 + stoptxt + (char) 4 + text1 + (char) 5 + text2;
        String hexstring = int2hexString(retString.length(), 2);
        return adresse + hexstring + retString;
    }

    private String int2hexString(int intNr, int retLength) {
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(intNr));
        if (sb.length() < retLength) {
            sb.insert(0, '0'); // pad with leading zero if needed
        }
        return sb.toString().toUpperCase();

    }

    private void ds10Btn(String stop) { //next Stop
        String stopStr = parseStop(stop);
        send("x00" + stopStr);  //DS10 x4Z
        send("xH00" + stopStr); //DS10a xH4Z Perlschnur
        send("xI" + stopStr);   //DS10b xI2Z Fortschaltung
        send("xZ" + stopStr);   //DS10c xZ2Z Perlschnur
    }

    private void datzeitBtn() {
        Date dat = new Date();
        String datstr = (String) android.text.format.DateFormat.format("ddMMyy", dat);
        String timestr = (String) android.text.format.DateFormat.format("HHmm", dat);

        send("d" + datstr);
        send("u" + timestr);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void show_ibiscodes() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("IBIS Codes");
        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNegativeButton("cancel", (dialog, which) -> dialog.dismiss());


        arrayAdapter = new ArrayAdapter<>(getActivity(), R.layout.dscodes_list_item, R.id.description, dsItems);

        builder.setAdapter(arrayAdapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int position) {
                dsItem item = arrayAdapter.getItem(position);
                String example;
                if (item != null) {
                    example = item.example;
                    TextView sendText = view.findViewById(R.id.send_text);
                    sendText.setText(example);
                }
            }
        });

        builder.create().show();


    }

    /**
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> {
            receive(data);
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    /**
     * Serial + UI
     */
    private void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), 0);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(1200, 7, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_ODD);
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                Executors.newSingleThreadExecutor().submit(usbIoManager);
            }
            status("connected");
            connected = true;

        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        if (usbIoManager != null)
            usbIoManager.stop();
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {
        }
        usbSerialPort = null;
    }

    private void send(String str) {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            str = str.replace("ä", "{");
            str = str.replace("ö", "|");
            str = str.replace("ü", "}");
            str = str.replace("ß", "~");
            str = str.replace("Ä", "[");
            str = str.replace("Ö", "\\");
            str = str.replace("Ü", "]");

            byte[] data = str.getBytes();

            byte xorByte = 0x7F;

            for (byte b : data) {
                xorByte = (byte) (b ^ xorByte);
            }
            xorByte = (byte) (0xff & (0x0D ^ xorByte));

            // 4. XOR Byte anhängen
            byte[] endBytes = {0x0D, xorByte};

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write(data);
            outputStream.write(endBytes);

            byte[] sendBytes = outputStream.toByteArray();

            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("send " + sendBytes.length + " bytes\n");
            spn.append(HexDump.dumpHexString(sendBytes) + "\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            int response = usbSerialPort.write(sendBytes, WRITE_WAIT_MILLIS);
            Toast.makeText(getActivity(), "Response:" + response, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void read() {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
            status("connection lost: " + e.getMessage());
            disconnect();
        }
    }

    private void receive(byte[] data) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        spn.append("receive " + data.length + " bytes\n");
        if (data.length > 0)
            spn.append(HexDump.dumpHexString(data) + "\n");
        receiveText.append(spn);
    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }


}
