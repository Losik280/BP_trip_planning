package com.example.myapplication.ui.history;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.myapplication.R;
import com.example.myapplication.data.MowingPlace;
import com.example.myapplication.data.MowingPlacesRepository;
import com.example.myapplication.data.RoutePlan;
import com.example.myapplication.data.RoutePlanRepository;
import com.example.myapplication.databinding.FragmentHistoryBinding;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Fragment that displays a list of route plans.
 * It uses a RecyclerView to show the list of RoutePlan objects.
 */
public class HistoryFragment extends Fragment {

    private static final String STATS_FORMAT = "Vzdálenost: %.1f km, Trvání: %.1f h";

    private FragmentHistoryBinding binding;
    private RoutePlanRepository routePlanRepository;
    private List<RoutePlan> routePlans;
    private HistoryAdapter historyAdapter;

    /**
     * Called when the fragment is created.
     * It initializes the RecyclerView and loads the route plans.
     *
     * @param inflater           The LayoutInflater used to inflate the fragment's view.
     * @param container          The parent view that this fragment's UI should be attached to.
     * @param savedInstanceState A Bundle containing the saved state of the fragment.
     * @return The root view of the fragment.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHistoryBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        setHasOptionsMenu(true);

        binding.recyclerHistory.setLayoutManager(new LinearLayoutManager(getContext()));

        routePlanRepository = new RoutePlanRepository();

        // Load & sort routes
        routePlans = routePlanRepository.loadRoutePlans(getContext());
        routePlans.sort((a, b) -> b.getDateTime().compareTo(a.getDateTime()));
        Log.d("HistoryFragment", "Loaded route plans: " + routePlans.size());

        // Initial empty-state toggle
        if (routePlans.isEmpty()) {
            binding.recyclerHistory.setVisibility(View.GONE);
            binding.tvEmptyHistory.setVisibility(View.VISIBLE);
        } else {
            binding.recyclerHistory.setVisibility(View.VISIBLE);
            binding.tvEmptyHistory.setVisibility(View.GONE);
        }

        // Attach adapter with empty-state callback
        historyAdapter = new HistoryAdapter(getContext(), routePlans, this::updateEmptyView);
        binding.recyclerHistory.setAdapter(historyAdapter);

        return root;
    }

    /**
     * Called when the view is destroyed.
     * It sets the binding to null to avoid memory leaks.
     */
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.history_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    /**
     * Handles menu item selection.
     * If the "Delete All" option is selected, it shows a confirmation dialog.
     *
     * @param item The selected menu item.
     * @return true if the menu item was handled, false otherwise.
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_delete_all) {
            new AlertDialog.Builder(getContext())
                    .setTitle("Potvrzení")
                    .setMessage("Opravdu chcete smazat všechny trasy?")
                    .setPositiveButton("Ano", (d, w) -> {
                        routePlans.clear();
                        routePlanRepository.saveRoutePlans(getContext(), routePlans);
                        historyAdapter.notifyDataSetChanged();
                        binding.recyclerHistory.setVisibility(View.GONE);
                        binding.tvEmptyHistory.setVisibility(View.VISIBLE);
                    })
                    .setNegativeButton("Ne", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Shows empty-state message when list is empty.
     */
    private void updateEmptyView() {
        if (routePlans.isEmpty()) {
            binding.recyclerHistory.setVisibility(View.GONE);
            binding.tvEmptyHistory.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Adapter for displaying route plans in a RecyclerView.
     */
    private static class HistoryAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

        private final Context context;
        private final List<RoutePlan> plans;
        private final RoutePlanRepository routeRepo;
        private final MowingPlacesRepository mowingRepo;
        private final LayoutInflater inflater;
        private final Runnable onEmpty;

        /**
         * Constructor for the HistoryAdapter.
         *
         * @param ctx    The context used to inflate views and access resources.
         * @param plans  The list of RoutePlan objects to display.
         * @param onEmpty A callback to run when the list becomes empty.
         */
        HistoryAdapter(Context ctx, List<RoutePlan> plans, Runnable onEmpty) {
            this.context = ctx;
            this.plans = plans;
            this.routeRepo = new RoutePlanRepository();
            this.mowingRepo = new MowingPlacesRepository();
            this.inflater = LayoutInflater.from(ctx);
            this.onEmpty = onEmpty;
        }

        /**
         * Creates a new ViewHolder for the RecyclerView.
         * Inflates the layout for each item in the list.
         *
         * @param parent   The parent view that this ViewHolder will be attached to.
         * @param viewType The type of the view to create.
         */

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = inflater.inflate(R.layout.route_plan_item, parent, false);
            return new ViewHolder(view);
        }

        /**
         * Binds the data to the ViewHolder.
         * Sets the creation date, stats, and handles button clicks.
         *
         * @param h   The ViewHolder to bind data to.
         * @param pos The position of the item in the list.
         */
        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            RoutePlan plan = plans.get(pos);
            h.tvCreationDate.setText("Vytvořeno: " + plan.getDateTime());
            String stats = String.format(
                    Locale.getDefault(),
                    STATS_FORMAT,
                    plan.getLength() / 1000.0,
                    plan.getDuration()
            );
            h.tvStats.setText(stats);

            // Build stops list
            h.llStopsContainer.removeAllViews();
            for (String name : plan.getRoutePlaces()) {
                if ("Start".equals(name) || "End".equals(name)) continue;
                View stopRow = inflater.inflate(R.layout.item_stop, h.llStopsContainer, false);
                TextView tv = stopRow.findViewById(R.id.tvStopName);
                ImageButton btn = stopRow.findViewById(R.id.btnCheckStop);
                tv.setText(name);
                btn.setOnClickListener(v -> {
                    Calendar c = Calendar.getInstance();
                    new DatePickerDialog(context,
                            (DatePicker dp, int y, int m, int d) -> {
                                String sel = String.format(Locale.getDefault(),
                                        "%04d-%02d-%02d", y, m + 1, d);
                                List<MowingPlace> places = mowingRepo.loadMowingPlaces(context);
                                boolean ok = false;
                                for (MowingPlace p : places) {
                                    if (name.equals(p.getName())) {
                                        p.getVisitDates().add(sel);
                                        mowingRepo.saveMowingPlaces(context, places);
                                        Toast.makeText(context,
                                                "Místo označeno jako dokončené",
                                                Toast.LENGTH_SHORT).show();
                                        ok = true;
                                        break;
                                    }
                                }
                                if (!ok) {
                                    Toast.makeText(context,
                                            "Místo neexistuje",
                                            Toast.LENGTH_SHORT).show();
                                }
                            },
                            c.get(Calendar.YEAR),
                            c.get(Calendar.MONTH),
                            c.get(Calendar.DAY_OF_MONTH)
                    ).show();
                });
                h.llStopsContainer.addView(stopRow);
            }

            // Delete single route
            h.btnDelete.setOnClickListener(v -> {
                int idx = h.getAdapterPosition();
                plans.remove(idx);
                routeRepo.saveRoutePlans(context, plans);
                notifyItemRemoved(idx);
                if (onEmpty != null && plans.isEmpty()) {
                    onEmpty.run();
                }
            });

            // External map buttons
            h.btnOpenMapy.setOnClickListener(v -> {
                if (!TextUtils.isEmpty(plan.getMapyCzUrl())) {
                    context.startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(plan.getMapyCzUrl())));
                }
            });
            h.btnOpenGoogle.setOnClickListener(v -> {
                if (!TextUtils.isEmpty(plan.getGoogleMapsUrl())) {
                    context.startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(plan.getGoogleMapsUrl())));
                }
            });
        }

        /**
         * Returns the number of items in the list.
         *
         * @return The size of the plans list.
         */
        @Override
        public int getItemCount() {
            return plans.size();
        }

        /**
         * ViewHolder class for holding the views for each item in the RecyclerView.
         * It contains references to the TextViews, LinearLayout, and Buttons.
         */
        static class ViewHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            TextView tvCreationDate, tvStats;
            LinearLayout llStopsContainer;
            Button btnOpenMapy, btnOpenGoogle, btnDelete;

            /**
             * Constructor for the ViewHolder.
             * It initializes the views using findViewById.
             *
             * @param v The root view of the item layout.
             */
            ViewHolder(@NonNull View v) {
                super(v);
                tvCreationDate = v.findViewById(R.id.tvCreationDate);
                tvStats = v.findViewById(R.id.tvStats);
                llStopsContainer = v.findViewById(R.id.llStopsContainer);
                btnOpenMapy = v.findViewById(R.id.btnOpenMapyCzRoute);
                btnOpenGoogle = v.findViewById(R.id.btnOpenGoogleMapsRoute);
                btnDelete = v.findViewById(R.id.btnDeleteRoutePlan);
            }
        }
    }
}
