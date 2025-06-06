package com.example.myapplication.ui.planning;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.example.myapplication.R;
import com.example.myapplication.data.MowingPlace;
import com.example.myapplication.data.MowingPlacesRepository;
import com.example.myapplication.data.RoutePlan;
import com.example.myapplication.data.RoutePlanRepository;
import com.example.myapplication.databinding.FragmentPlanningBinding;
import com.example.myapplication.util.DiacriticInsensitiveAdapter;
import com.example.myapplication.util.MatrixApiHelper;
import com.example.myapplication.util.TSPPlanner;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fragment for planning a route using the TSP algorithm.
 * Users can select start and end locations, add waypoints, and generate a route.
 * The generated route can be viewed in Mapy.cz and Google Maps.
 */
public class PlanningFragment extends Fragment {

    private FragmentPlanningBinding binding;
    private MapView planningMapView;
    private NestedScrollView nestedScrollView;

    // UI elements for location and time inputs
    private EditText etStartLocation, etEndLocation;
    private TextView tvStartTime, tvEndTime;
    private SeekBar sbSpeedMultiplier;
    private CheckBox cbAddExtra;
    private Button btnGenerateRoute, btnOpenMapycZ, btnOpenGoogleMaps, btnAddWaypoint;

    // Containers for dynamic entries: waypoints and extra options
    private ViewGroup llWaypoints;
    private ViewGroup llExtraOptions;

    // Extra options UI elements
    private EditText etLastMowingTime;
    private CheckBox cbIncludeVisited;

    // Repository for available cemeteries and mapping from name to MowingPlace
    private MowingPlacesRepository placesRepository;
    private List<MowingPlace> availablePlaces;
    private Map<String, MowingPlace> nameToPlace;

    // Default time constraints (in minutes from midnight)
    private int startTimeInMinutes = 360; // default 06:00
    private int endTimeInMinutes = 1140;  // default 19:00

    // Route plan variables
    private List<MowingPlace> finalRoute;
    private double totalMowingTime; // computed total time (driving + mowing)
    private String mapyCzRouteUrl = "";
    private String googleMapsUrl = "";

    private static final int REQUEST_CODE_START = 101;
    private static final int REQUEST_CODE_END = 102;

    /**
     * Called when the fragment is created.
     * It initializes the UI components and sets up listeners for user interactions.
     *
     * @param inflater           The LayoutInflater used to inflate the fragment's view.
     * @param container          The parent view that this fragment's UI should be attached to.
     * @param savedInstanceState A Bundle containing the saved state of the fragment.
     * @return The root view of the fragment.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentPlanningBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        // Since the root is a NestedScrollView, keep a reference
        nestedScrollView = (NestedScrollView) root;

        // Initialize UI components
        etStartLocation = binding.etStartLocation;
        etEndLocation = binding.etEndLocation;
        tvStartTime = binding.tvStartTime;
        tvEndTime = binding.tvEndTime;
        sbSpeedMultiplier = binding.seekBarSpeedMultiplier;
        cbAddExtra = binding.cbAddExtra;
        btnGenerateRoute = binding.btnGenerateRoute;
        btnOpenMapycZ = binding.btnOpenMapycZ;
        btnOpenGoogleMaps = binding.btnOpenGoogleMaps;
        btnAddWaypoint = binding.btnAddWaypoint;
        planningMapView = binding.planningMapView;
        // Get the waypoint container (llWaypoints) and extra options container (llExtraOptions)
        llWaypoints = binding.llWaypoints;
        llExtraOptions = binding.llExtraOptions;

        // Extra options: initialize etLastMowingTime and cbExcludeVisited
        etLastMowingTime = llExtraOptions.findViewById(R.id.etLastMowingTime);
        cbIncludeVisited = llExtraOptions.findViewById(R.id.cbIncludeVisited);
        etLastMowingTime.setKeyListener(DigitsKeyListener.getInstance("0123456789"));

        // Hide "Open in Mapy.cz" button initially
        btnOpenMapycZ.setVisibility(View.GONE);
        btnOpenGoogleMaps.setVisibility(View.GONE);

        // Initialize map view
        planningMapView.setMultiTouchControls(true);
        planningMapView.getController().setZoom(7.5);
        planningMapView.getController().setCenter(new GeoPoint(49.8175, 15.4730));
        // Prevent parent from intercepting touch events
        planningMapView.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        // Initialize repository and load available places
        placesRepository = new MowingPlacesRepository();
        availablePlaces = placesRepository.loadMowingPlaces(getContext());
        // Build mapping for auto-complete suggestions (diacritics will be ignored by adapter)
        nameToPlace = new ArrayMap<>();
        List<String> placeNames = new ArrayList<>();
        for (MowingPlace mp : availablePlaces) {
            nameToPlace.put(mp.getName(), mp);
            placeNames.add(mp.getName());
        }

        // Set default times and update TextViews
        tvStartTime.setText(formatTime(startTimeInMinutes));
        tvEndTime.setText(formatTime(endTimeInMinutes));

        // Time picker listeners; ensure dialog opens na on firs click
        tvStartTime.setOnClickListener(v -> {
            showTimePicker(startTimeInMinutes, (hour, minute) -> {
                startTimeInMinutes = hour * 60 + minute;
                tvStartTime.setText(formatTime(startTimeInMinutes));
            });
        });
        tvEndTime.setOnClickListener(v -> {
            showTimePicker(endTimeInMinutes, (hour, minute) -> {
                endTimeInMinutes = hour * 60 + minute;
                tvEndTime.setText(formatTime(endTimeInMinutes));
            });
        });

        // SeekBar for speed multiplier (multiplier = 0.5 + progress*0.5)
        sbSpeedMultiplier.setMax(5);
        sbSpeedMultiplier.setProgress(1);
        double speedMultiplier = 0.5 + (sbSpeedMultiplier.getProgress() * 0.5);
        binding.tvSpeedMultiplierValue.setText("Rychlost práce: " + speedMultiplier + " x");
        sbSpeedMultiplier.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double multiplier = 0.5 + (progress * 0.5);
                binding.tvSpeedMultiplierValue.setText("Rychlost práce: " + multiplier + "x");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // Location picker listeners
        etStartLocation.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), LocationPickerActivity.class);
            startActivityForResult(intent, REQUEST_CODE_START);
        });
        etEndLocation.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), LocationPickerActivity.class);
            startActivityForResult(intent, REQUEST_CODE_END);
        });

        // Add Waypoint button listener: only add new waypoint if last one is filled
        btnAddWaypoint.setOnClickListener(v -> {
            if (llWaypoints.getChildCount() > 0) {
                View lastChild = llWaypoints.getChildAt(llWaypoints.getChildCount() - 1);
                AutoCompleteTextView lastActv = lastChild.findViewById(R.id.actvWaypoint);
                if (TextUtils.isEmpty(lastActv.getText().toString().trim())) {
                    Toast.makeText(getContext(), "Prosím, vyplňte poslední místo průjezdu", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            addWaypointEntry();
        });

        // Listener for extra options visibility based on cbAddExtra state
        cbAddExtra.setOnCheckedChangeListener((buttonView, isChecked) -> {
            llExtraOptions.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Generate Route button listener: close keyboard a scroll to bottom
        btnGenerateRoute.setOnClickListener(v -> {
            closeKeyboard();
            generateRoute();
            scrollToBottom();
        });

        // Open Mapy.cz button listener
        btnOpenMapycZ.setOnClickListener(v -> openMapyCz());
        // Open Google Maps button listener
        btnOpenGoogleMaps.setOnClickListener(v -> openGoogleMaps());

        return root;
    }

    /**
     * Hides the keyboard if it is open.
     */
    private void closeKeyboard() {
        View view = getActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager)getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Scrolls the NestedScrollView to the bottom.
     */
    private void scrollToBottom() {
        nestedScrollView.post(() -> nestedScrollView.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Handles the result from the LocationPickerActivity.
     * @param requestCode The integer request code originally supplied to
     *                    startActivityForResult(), allowing you to identify who this
     *                    result came from.
     * @param resultCode The integer result code returned by the child activity
     *                   through its setResult().
     * @param data An Intent, which can return result data to the caller
     *               (various data can be attached to Intent "extras").
     *
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == getActivity().RESULT_OK && data != null) {
            double lat = data.getDoubleExtra(LocationPickerActivity.EXTRA_SELECTED_LAT, 0);
            double lon = data.getDoubleExtra(LocationPickerActivity.EXTRA_SELECTED_LON, 0);
            String coordinates = lat + "," + lon;
            if(requestCode == REQUEST_CODE_START) {
                etStartLocation.setText(coordinates);
            } else if(requestCode == REQUEST_CODE_END) {
                etEndLocation.setText(coordinates);
            }
        }
    }

    /**
     * Adds a new waypoint entry to the list of waypoints.
     */
    private void addWaypointEntry() {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View waypointView = inflater.inflate(R.layout.waypoint_item, llWaypoints, false);
        AutoCompleteTextView actvWaypoint = waypointView.findViewById(R.id.actvWaypoint);
        ImageButton btnRemove = waypointView.findViewById(R.id.btnRemoveWaypoint);
        //set cursor to this new line
        actvWaypoint.requestFocus();

        // Use custom adapter ignoring diacritics
        ArrayAdapter<String> adapter = new DiacriticInsensitiveAdapter<>(getContext(), android.R.layout.simple_dropdown_item_1line, new ArrayList<>(nameToPlace.keySet()));
        actvWaypoint.setThreshold(1);
        actvWaypoint.setAdapter(adapter);

        // On item click, set tag with corresponding MowingPlace object
        actvWaypoint.setOnItemClickListener((parent, view, position, id) -> {
            String selectedName = (String) parent.getItemAtPosition(position);
            if (nameToPlace.containsKey(selectedName)) {
                actvWaypoint.setTag(nameToPlace.get(selectedName));
            }
        });

        // Remove button listener
        btnRemove.setOnClickListener(v -> llWaypoints.removeView(waypointView));

        llWaypoints.addView(waypointView);
    }

    /**
     * Generates the route based on user inputs and available places.
     * Validates inputs, retrieves distances, and computes the route using TSP algorithm.
     */
    private void generateRoute() {
        // Parse start and end location (format "lat,lon")
        String startLocStr = etStartLocation.getText().toString().trim();
        String endLocStr = etEndLocation.getText().toString().trim();
        if (TextUtils.isEmpty(startLocStr) || TextUtils.isEmpty(endLocStr)) {
            Toast.makeText(getContext(), "Prosím, vyberte startovní a koncovou lokaci", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] startParts = startLocStr.split(",");
        String[] endParts = endLocStr.split(",");
        if (startParts.length != 2 || endParts.length != 2) {
            Toast.makeText(getContext(), "Formát lokace musí být 'zem. šířka,zem. délka'", Toast.LENGTH_SHORT).show();
            return;
        }
        double startLat = Double.parseDouble(startParts[0].trim());
        double startLon = Double.parseDouble(startParts[1].trim());
        double endLat = Double.parseDouble(endParts[0].trim());
        double endLon = Double.parseDouble(endParts[1].trim());

        int startTime = startTimeInMinutes;
        int endTime = endTimeInMinutes;
        double speedMultiplier = 0.5 + (sbSpeedMultiplier.getProgress() * 0.5);

        // Create start and end nodes
        MowingPlace startPlace = new MowingPlace();
        startPlace.setId("start");
        startPlace.setName("Start");
        startPlace.setLatitude(startLat);
        startPlace.setLongitude(startLon);
        startPlace.setTimeRequirement(0);

        MowingPlace endPlace = new MowingPlace();
        endPlace.setId("end");
        endPlace.setName("End");
        endPlace.setLatitude(endLat);
        endPlace.setLongitude(endLon);
        endPlace.setTimeRequirement(0);

        // Retrieve mandatory waypoints
        List<MowingPlace> mandatoryWaypoints = new ArrayList<>();
        int count = llWaypoints.getChildCount();
        for (int i = 0; i < count; i++) {
            View waypointView = llWaypoints.getChildAt(i);
            AutoCompleteTextView actv = waypointView.findViewById(R.id.actvWaypoint);
            if (actv != null && !TextUtils.isEmpty(actv.getText().toString().trim())) {
                Object tag = actv.getTag();
                if (tag instanceof MowingPlace) {
                    mandatoryWaypoints.add((MowingPlace) tag);
                }
            }
        }

        if (mandatoryWaypoints.isEmpty() && !cbAddExtra.isChecked()) {
            Toast.makeText(getContext(), "Přidejte místo průjezdu, nebo vyberte automatické doplnění.", Toast.LENGTH_LONG).show();
            return;
        }

        // remove duplicates from mandatoryWaypoints
        List<MowingPlace> uniqueMandatoryWaypoints = new ArrayList<>();
        for (MowingPlace mp : mandatoryWaypoints) {
            if (!uniqueMandatoryWaypoints.contains(mp)) {
                uniqueMandatoryWaypoints.add(mp);
            }
        }
        mandatoryWaypoints = uniqueMandatoryWaypoints;

        // Build the complete list: start, mandatory waypoints, end
        List<MowingPlace> nodes = new ArrayList<>();
        nodes.add(startPlace);
        nodes.addAll(mandatoryWaypoints);
        nodes.add(endPlace);

        // Prepare allPlaces list for distance updates
        List<MowingPlace> allPlaces = new ArrayList<>(availablePlaces);

        // Use an AtomicInteger to count completed update attempts (for start and end)
        final AtomicInteger updatesCompleted = new AtomicInteger(0);

        // Helper method to check if both updates are done
        Runnable checkAndGenerateRoute = () -> {
            if (updatesCompleted.get() >= 2) {
                // Both start and end updates have finished (successfully or not)
                // Now proceed with route generation:
                finalRoute = new ArrayList<>(TSPPlanner.generateRoute(nodes));
                if (cbAddExtra.isChecked()) {
                    // Add extra cemeteries if checkbox is checked
                    //try to parse last mowing time to int
                    int lastMowingTime = 0;
                    if (etLastMowingTime.getText() != null && !TextUtils.isEmpty(etLastMowingTime.getText().toString())) {
                        try {
                            lastMowingTime = Integer.parseInt(etLastMowingTime.getText().toString());
                        } catch (NumberFormatException e) {
                            Toast.makeText(getContext(), "Čas posledního sekání není platný", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    finalRoute = TSPPlanner.addExtraCemeteries(finalRoute, availablePlaces, endTime - startTime, speedMultiplier, cbIncludeVisited.isChecked(), lastMowingTime);
                }
                totalMowingTime = 0;
                for (MowingPlace mp : finalRoute) {
                    totalMowingTime += mp.getTimeRequirement();
                }
                totalMowingTime /= speedMultiplier;

                //go through finalRoute and add the driving time to each place using distance entry duration
                for (int i = 0; i < finalRoute.size(); i++) {
                    MowingPlace mp = finalRoute.get(i);
                    if (i > 0) {
                        MowingPlace previousMp = finalRoute.get(i - 1);
                        //avoid null pointer exception
                        if (previousMp.getDistancesToOthers() == null) {
                            continue;
                        }
                        for (MowingPlace.DistanceEntry entry : previousMp.getDistancesToOthers()) {
                            if (entry.getId().equals(mp.getId())) {
                                totalMowingTime += entry.getDuration() / 3600.0; // convert seconds to hours
                                break;
                            }
                        }
                    }
                }
                String formattedMowingTime = String.format("%.1f", totalMowingTime);

                //find out total distance
                double totalDistance = 0;
                for (int i = 0; i < finalRoute.size() - 1; i++) {
                    MowingPlace mp = finalRoute.get(i);
                    MowingPlace nextMp = finalRoute.get(i + 1);
                    if (mp.getDistancesToOthers() == null) {
                        continue;
                    }
                    for (MowingPlace.DistanceEntry entry : mp.getDistancesToOthers()) {
                        if (entry.getId().equals(nextMp.getId())) {
                            totalDistance += entry.getDistance();
                            break;
                        }
                    }
                }


                mapyCzRouteUrl = generateMapyUrl(finalRoute);
                googleMapsUrl = generateGoogleMapsUrl(finalRoute);
                updateMapPreview(finalRoute);
                btnOpenMapycZ.setVisibility(View.VISIBLE);
                btnOpenGoogleMaps.setVisibility(View.VISIBLE);

                // Save route plan to repository
                RoutePlan routePlan = new RoutePlan();
                routePlan.setRoutePlaces(finalRoute);
                routePlan.setMapyCzUrl(mapyCzRouteUrl);
                routePlan.setGoogleMapsUrl(googleMapsUrl);
                routePlan.setDuration(totalMowingTime);
                routePlan.setLength(totalDistance);
                routePlan.setDateTime(String.format("%tF %tT", System.currentTimeMillis(), System.currentTimeMillis()));
                RoutePlanRepository routePlanRepository = new RoutePlanRepository();
                List<RoutePlan> routePlans = routePlanRepository.loadRoutePlans(getContext());
                if (routePlans == null) {
                    routePlans = new ArrayList<>();
                }
                routePlans.add(routePlan);
                routePlanRepository.saveRoutePlans(getContext(), routePlans);

                //format distance to km
                String formattedDistance = String.format("%.1f", totalDistance / 1000);
                Toast.makeText(getContext(), "Trasa vytvořena. Přibližný čas: " +
                        formattedMowingTime + " h" + ", přibližná vzdálenost: " + formattedDistance
                        + " km", Toast.LENGTH_LONG).show();

                planningMapView.getController().setZoom(7.5);
                planningMapView.getController().setCenter(new GeoPoint(49.8175, 15.4730));
                scrollToBottom();
            }
        };

        // Update distances for startPlace
        MatrixApiHelper.updateDistances(getContext(), startPlace, allPlaces, new MatrixApiHelper.MatrixApiCallback() {
            @Override
            public void onSuccess() {
                updatesCompleted.incrementAndGet();
                checkAndGenerateRoute.run();
            }
            @Override
            public void onFailure(String errorMessage) {
                Toast.makeText(getContext(), "Zjišťování vzdálenosti startu a cíle se nezdařilo, výsledek může být nepřesný.", Toast.LENGTH_SHORT).show();
                updatesCompleted.incrementAndGet();
                checkAndGenerateRoute.run();
            }
        });

        // Update distances for endPlace
        MatrixApiHelper.updateDistances(getContext(), endPlace, allPlaces, new MatrixApiHelper.MatrixApiCallback() {
            @Override
            public void onSuccess() {
                updatesCompleted.incrementAndGet();
                checkAndGenerateRoute.run();
            }
            @Override
            public void onFailure(String errorMessage) {
                updatesCompleted.incrementAndGet();
                checkAndGenerateRoute.run();
            }
        });
    }

    /**
     * Updates the map preview with the generated route.
     * Clears previous overlays and adds a new polyline for the route.
     *
     * @param route The list of MowingPlace objects representing the route.
     */
    private void updateMapPreview(List<MowingPlace> route) {
        planningMapView.getOverlays().clear();
        Polyline polyline = new Polyline();
        List<GeoPoint> geoPoints = new ArrayList<>();
        for (MowingPlace mp : route) {
            geoPoints.add(new GeoPoint(mp.getLatitude(), mp.getLongitude()));
        }
        polyline.setPoints(geoPoints);
        planningMapView.getOverlays().add(polyline);
        planningMapView.invalidate();
    }

    /**
     * Generates a Mapy.cz URL for the route.
     * @param route The list of MowingPlace objects representing the route.
     * @return The generated Mapy.cz URL.
     */
    private String generateMapyUrl(List<MowingPlace> route) {
        if (route.size() < 2) {
            return "";
        }
        MowingPlace start = route.get(0);
        MowingPlace end = route.get(route.size() - 1);
        StringBuilder waypointsBuilder = new StringBuilder();
        for (int i = 1; i < route.size() - 1; i++) {
            MowingPlace mp = route.get(i);
            if (waypointsBuilder.length() > 0) {
                waypointsBuilder.append(";");
            }
            waypointsBuilder.append(mp.getLongitude()).append(",").append(mp.getLatitude());
        }
        String url = "https://mapy.cz/fnc/v1/route?mapset=traffic";
        url += "&start=" + start.getLongitude() + "," + start.getLatitude();
        url += "&end=" + end.getLongitude() + "," + end.getLatitude();
        url += "&routeType=car_fast";
        if (waypointsBuilder.length() > 0) {
            url += "&waypoints=" + waypointsBuilder;
        }
        return url;
    }

    /**
     * Generates a Google Maps URL for the route.
     * @param route The list of MowingPlace objects representing the route.
     * @return The generated Google Maps URL.
     */
    private String generateGoogleMapsUrl(List<MowingPlace> route) {
        if (route.size() < 2)
            return "";
        MowingPlace start = route.get(0);
        MowingPlace end = route.get(route.size() - 1);
        StringBuilder waypointsBuilder = new StringBuilder();
        // Google Maps API očekává waypointy oddělené svislou čarou
        for (int i = 1; i < route.size() - 1; i++) {
            MowingPlace mp = route.get(i);
            if (waypointsBuilder.length() > 0) {
                waypointsBuilder.append("|");
            }
            waypointsBuilder.append(mp.getLatitude()).append(",").append(mp.getLongitude());
        }
        StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1");
        url.append("&origin=").append(start.getLatitude()).append(",").append(start.getLongitude());
        url.append("&destination=").append(end.getLatitude()).append(",").append(end.getLongitude());
        if (waypointsBuilder.length() > 0) {
            url.append("&waypoints=").append(waypointsBuilder);
        }
        url.append("&travelmode=driving");
        return url.toString();
    }

    /**
     * Formats the time in minutes to a string in the format "HH:MM".
     * @param totalMinutes The total time in minutes.
     * @return The formatted time string.
     */
    private String formatTime(int totalMinutes) {
        int hour = totalMinutes / 60;
        int minute = totalMinutes % 60;
        return String.format("%02d:%02d", hour, minute);
    }

    /**
     * Callback interface for time selection.
     */
    public interface TimePickerCallback {
        /**
         * Called when the time is selected.
         * @param hour The selected hour.
         * @param minute The selected minute.
         */
        void onTimeSelected(int hour, int minute);
    }

    /**
     * Shows a time picker dialog for selecting time.
     * @param initialMinutes The initial time in minutes.
     * @param callback The callback to handle the selected time.
     */
    private void showTimePicker(int initialMinutes, TimePickerCallback callback) {
        int initialHour = initialMinutes / 60;
        int initialMinute = initialMinutes % 60;
        new android.app.TimePickerDialog(getContext(), (view, hourOfDay, minute) -> {
            callback.onTimeSelected(hourOfDay, minute);
        }, initialHour, initialMinute, true).show();
    }

    /**
     * Opens the Mapy.cz route in a browser.
     */
    private void openMapyCz() {
        if (mapyCzRouteUrl == null || mapyCzRouteUrl.isEmpty()) {
            Toast.makeText(getContext(), "Trasa není vygenerována", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapyCzRouteUrl));
        startActivity(intent);
    }

    /**
     * Opens the Google Maps route in a browser.
     */
    private void openGoogleMaps() {
        if (googleMapsUrl == null || googleMapsUrl.isEmpty()) {
            Toast.makeText(getContext(), "Trasa není vygenerována", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsUrl));
        startActivity(intent);
    }
}
