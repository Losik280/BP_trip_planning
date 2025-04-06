package com.example.myapplication.data;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

public class MowingPlacesRepository {

    private static final String TAG = "MowingPlacesRepository";
    private static final String JSON_FILE_NAME = "mowing_places_test.json";

    //highest id in loaded places
    private int highestId = 0;

    public List<MowingPlace> loadMowingPlaces(Context context) {
        try {
            File file = new File(context.getFilesDir(), JSON_FILE_NAME);
            InputStream is;
            if (file.exists()) {
                // Load from internal storage
                is = new FileInputStream(file);
                Log.d(TAG, "Loading JSON from internal storage");
            } else {
                // Load from assets if file does not exist in internal storage
                is = context.getAssets().open(JSON_FILE_NAME);
                Log.d(TAG, "Loading JSON from assets");
            }
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String jsonString = new String(buffer, "UTF-8");

            Gson gson = new Gson();
            Type listType = new TypeToken<List<MowingPlace>>() {}.getType();
            List<MowingPlace> places = gson.fromJson(jsonString, listType);

            // Find the highest ID in the loaded places
            for (MowingPlace place : places) {
                if (Integer.parseInt(place.getId()) > highestId) {
                    highestId =  Integer.parseInt(place.getId());
                }
            }

            return places;
        } catch (IOException e) {
            Log.e(TAG, "Error reading JSON file", e);
            return Collections.emptyList();
        }
    }

    // Saves the provided list of places into internal storage as JSON.
    public boolean saveMowingPlaces(Context context, List<MowingPlace> places) {
        try {
            Gson gson = new Gson();
            String jsonString = gson.toJson(places);
            File file = new File(context.getFilesDir(), JSON_FILE_NAME);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(jsonString.getBytes("UTF-8"));
            fos.close();
            Log.d(TAG, "Mowing places saved to " + file.getAbsolutePath());
            loadMowingPlaces(context); // Reload the data after saving
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error saving JSON file", e);
            return false;
        }
    }
}
