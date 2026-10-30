package com.moveinsync.evfleet;

import com.moveinsync.evfleet.planner.Planner;

public class CrashChild {

    public static void main(String[] args) {
        var repo = DatabaseSupport.repository();
        var job = repo.claim(args[0], 5000);
        var p = Planner.standard().solve(repo.dataset(job.tenant(), job.revision()), 1, null);
        repo.publish(job, p, stage -> {
            if (stage.equals("after_plan_insert")) {
                Runtime.getRuntime().halt(77);
            }
        });
    }
}
