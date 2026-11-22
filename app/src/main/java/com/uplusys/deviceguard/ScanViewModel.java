package com.uplusys.deviceguard;

import androidx.lifecycle.ViewModel;

public final class ScanViewModel extends ViewModel {
    ScanService.Snapshot scan = ScanService.Snapshot.idle();
    int tab = R.id.nav_scan;
    boolean candidatesOnly = true;
    String selected;
    String findingKey;
    String selectedSession;
    String routeSession;
    String routeObservation;
    boolean routeFind;
    boolean starting;
}
