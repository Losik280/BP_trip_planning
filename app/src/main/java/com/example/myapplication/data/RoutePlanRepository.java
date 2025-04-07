package com.example.myapplication.data;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * Repository for saving and loading route plans.
 */
public class RoutePlanRepository {

    private static final String TAG = "RoutePlanRepository";
    private static final String JSON_FILE_NAME = "route_plans.json";

    public List<RoutePlan> loadRoutePlans(Context context) {
        try {
            File file = new File(context.getFilesDir(), JSON_FILE_NAME);
            InputStream is;
            if (file.exists()) {
                is = new FileInputStream(file);
            } else {
                // If file does not exist, return empty list
                return Collections.emptyList();
            }
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String jsonString = new String(buffer, "UTF-8");

            Gson gson = new Gson();
            Type listType = new TypeToken<List<RoutePlan>>() {}.getType();
            return gson.fromJson(jsonString, listType);
        } catch (Exception e) {
            Log.e(TAG, "Error loading route plans", e);
            return Collections.emptyList();
        }
    }

    public boolean saveRoutePlans(Context context, List<RoutePlan> plans) {
        try {
            Gson gson = new Gson();
            String jsonString = gson.toJson(plans);
            File file = new File(context.getFilesDir(), JSON_FILE_NAME);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(jsonString.getBytes("UTF-8"));
            fos.close();
            Log.d(TAG, "Route plans saved.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving route plans", e);
            return false;
        }
    }
}
