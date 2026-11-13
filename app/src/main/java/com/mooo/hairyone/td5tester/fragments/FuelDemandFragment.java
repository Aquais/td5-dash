package com.mooo.hairyone.td5tester.fragments;

import android.view.View;

import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.views.GaugeInfoSheet;
import com.mooo.hairyone.td5tester.views.GaugeView;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Mapping values. The original notes are explicit that there is no good or bad
 * reading here, so nothing on this tab raises an alert.
 */
public class FuelDemandFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    GaugeView gDRIVER_DEMAND, gMAF_AIR_MASS, gMAP_AIR_MASS, gINJECTION_QUANTITY;
    GaugeView gAF_RATIO, gTORQUE_LIMIT, gSMOKE_LIMIT, gIDLE_DEMAND;

    public FuelDemandFragment() { /* Required empty public constructor */ }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDashboardEvent(DashboardEvent event) {
        float value = (float) event.value;
        switch (event.data_type) {
            case DRIVER_DEMAND:      gDRIVER_DEMAND.setValue(value); break;
            case MAF_AIR_MASS:       gMAF_AIR_MASS.setValue(value); break;
            case MAP_AIR_MASS:       gMAP_AIR_MASS.setValue(value); break;
            case INJECTION_QUANTITY: gINJECTION_QUANTITY.setValue(value); break;
            case AF_RATIO:           gAF_RATIO.setValue(value); break;
            case TORQUE_LIMIT:       gTORQUE_LIMIT.setValue(value); break;
            case SMOKE_LIMIT:        gSMOKE_LIMIT.setValue(value); break;
            case IDLE_DEMAND:        gIDLE_DEMAND.setValue(value); break;
        }
    }

    @Override protected int getFragmentLayout() { return R.layout.fuel_demand_fragment; }

    @Override protected void bindViews(View view) {
        gDRIVER_DEMAND = view.findViewById(R.id.gDRIVER_DEMAND);
        gMAF_AIR_MASS = view.findViewById(R.id.gMAF_AIR_MASS);
        gMAP_AIR_MASS = view.findViewById(R.id.gMAP_AIR_MASS);
        gINJECTION_QUANTITY = view.findViewById(R.id.gINJECTION_QUANTITY);
        gAF_RATIO = view.findViewById(R.id.gAF_RATIO);
        gTORQUE_LIMIT = view.findViewById(R.id.gTORQUE_LIMIT);
        gSMOKE_LIMIT = view.findViewById(R.id.gSMOKE_LIMIT);
        gIDLE_DEMAND = view.findViewById(R.id.gIDLE_DEMAND);

        // Idle demand is the gap to the target idle, so it reads from zero.
        gIDLE_DEMAND.setOrigin(0f);

        gDRIVER_DEMAND.setInfo(getString(R.string.t_mapping), getString(R.string.i_mapping));
        gTORQUE_LIMIT.setInfo(getString(R.string.t_mapping), getString(R.string.i_mapping));
        gSMOKE_LIMIT.setInfo(getString(R.string.t_mapping), getString(R.string.i_mapping));
        gINJECTION_QUANTITY.setInfo(getString(R.string.t_mapping), getString(R.string.i_mapping));
        gIDLE_DEMAND.setInfo(getString(R.string.t_idle), getString(R.string.i_idle));
        gMAF_AIR_MASS.setInfo(getString(R.string.t_maf), getString(R.string.i_maf));
        gMAP_AIR_MASS.setInfo(getString(R.string.t_map), getString(R.string.i_map));
        gAF_RATIO.setInfo(getString(R.string.t_afr), getString(R.string.i_afr));
        GaugeInfoSheet.attach(gDRIVER_DEMAND, gTORQUE_LIMIT, gSMOKE_LIMIT, gINJECTION_QUANTITY, gIDLE_DEMAND, gMAF_AIR_MASS, gMAP_AIR_MASS, gAF_RATIO);
    }
}
