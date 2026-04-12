package org.telegram.messenger;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

// оно будто само работает

public class ByeDpiRunner {

    private static volatile ByeDpiRunner Instance;
    private Process process;
    private Context appContext;
    private boolean isStopping = false;

    public static ByeDpiRunner getInstance() {
        if (Instance == null) {
            synchronized (ByeDpiRunner.class) {
                if (Instance == null) {
                    Instance = new ByeDpiRunner();
                }
            }
        }
        return Instance;
    }

    public void start(Context context) {
        if (context == null) return;
        this.appContext = context.getApplicationContext();
        isStopping = false;
        stop();

        try {
            String libPath = appContext.getApplicationInfo().nativeLibraryDir + "/libciadpi.so";
            File libFile = new File(libPath);

            if (!libFile.exists()) {
                Log.e("ByeDPI", "Критическая ошибка: libciadpi.so не найден: " + libPath);
                return;
            }

            try {
                Runtime.getRuntime().exec("chmod 755 " + libPath).waitFor();
            } catch (Exception ignored) {}

            List<String> command = new ArrayList<>();
            command.add(libPath);
            command.add("-p"); command.add("1081");
            command.add("-i"); command.add("127.0.0.1");

            command.add("-s"); command.add("1");

            command.add("-f"); command.add("-1");

            command.add("-t"); command.add("5");

            command.add("-I"); command.add("0.0.0.0");
            command.add("-U");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            process = pb.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d("ByeDPI_Log", line);
                    }

                    int exitCode = process.waitFor();
                    Log.e("ByeDPI", "Процесс завершился с кодом: " + exitCode);

                    if (!isStopping && exitCode != 0) {
                        Log.w("ByeDPI", "Краш процесса, перезапуск через 3 сек...");
                        Thread.sleep(3000);
                        if (!isStopping) {
                            start(appContext);
                        }
                    }
                } catch (Exception e) {
                    Log.e("ByeDPI", "Ошибка мониторинга: " + e.getMessage());
                }
            }, "ByeDpiMonitorThread").start();

            Log.d("ByeDPI", "Процесс запущен в режиме Jammer (DPI Clogging).");

        } catch (Exception e) {
            Log.e("ByeDPI", "Критическая ошибка старта: " + e.getMessage());
        }
    }

    public void stop() {
        isStopping = true;
        if (process != null) {
            Log.d("ByeDPI", "Остановка процесса...");
            process.destroy();
            process = null;
        }
    }
}
