const element = (id) => document.getElementById(id);

class FleetTimeline {
    constructor() {
        this.currentPlan = null;
        element("load").addEventListener("click", () => this.loadCurrentPlan());
        element("validate").addEventListener("click", () => this.validateStoredPlan());
    }

    async request(path, method = "GET") {
        const response = await fetch(path, {
            method,
            headers: {
                Authorization: `Bearer ${element("token").value}`,
            },
        });
        const body = await response.json();

        if (!response.ok) {
            throw new Error(JSON.stringify(body));
        }

        return body;
    }

    async loadCurrentPlan() {
        try {
            this.currentPlan = await this.request("/api/v1/plans/current");
            this.renderSummary();
            this.renderTimeline();
            element("backups").textContent = JSON.stringify(this.currentPlan.plan.backups, null, 2);
        } catch (error) {
            element("status").textContent = error.message;
        }
    }

    async validateStoredPlan() {
        if (!this.currentPlan) {
            return;
        }

        try {
            const report = await this.request(
                `/api/v1/plans/${this.currentPlan.id}/validate`,
                "POST",
            );
            element("status").textContent = JSON.stringify(report, null, 2);
        } catch (error) {
            element("status").textContent = error.message;
        }
    }

    renderSummary() {
        const current = this.currentPlan;
        const electricityCost = (current.mutableCostMicros / 1e6).toFixed(2);

        element("status").textContent =
            `Revision ${current.inputRevision} · ${current.status} · ` +
            `Dispatchable: ${current.dispatchable}`;
        element("summary").textContent =
            `${current.totalTripCount} trips including retained commitments · ` +
            `${current.totalBackupCount} total backups · ` +
            `${electricityCost} editable-suffix electricity cost · ` +
            `Valid: ${current.plan.validation.valid}`;
    }

    renderTimeline() {
        const plan = this.currentPlan.plan;
        const actions = [
            ...plan.history.map((action) => ({
                ...action,
                phase: action.end <= (plan.asOf ?? -1) ? "completed" : "retained future",
            })),
            ...plan.actions.map((action) => ({ ...action, phase: "editable future" })),
        ];
        const actionsByVehicle = new Map();

        for (const action of actions) {
            if (!actionsByVehicle.has(action.vehicleId)) {
                actionsByVehicle.set(action.vehicleId, []);
            }
            actionsByVehicle.get(action.vehicleId).push(action);
        }

        element("timeline").replaceChildren();
        element("details").replaceChildren();

        if (actions.length === 0) {
            return;
        }

        const start = Math.min(...actions.map((action) => action.start));
        const end = Math.max(...actions.map((action) => action.end));

        for (const [vehicleId, vehicleActions] of actionsByVehicle) {
            this.renderVehicle(vehicleId, vehicleActions, start, end);
        }
    }

    renderVehicle(vehicleId, actions, start, end) {
        const row = document.createElement("div");
        row.className = "row";

        const label = document.createElement("b");
        label.className = "label";
        label.textContent = vehicleId;

        const track = document.createElement("div");
        track.className = "track";
        row.append(label, track);

        for (const action of actions) {
            const bar = document.createElement("div");
            bar.className = `bar ${action.kind}`;
            bar.style.left = `${((action.start - start) / (end - start)) * 100}%`;
            bar.style.width = `${((action.end - action.start) / (end - start)) * 100}%`;
            bar.textContent = action.kind;
            bar.title =
                `${action.kind} ${action.start}–${action.end} · ` +
                `${action.beforeWh} → ${action.afterWh} Wh`;
            track.append(bar);

            this.renderActionDetails(vehicleId, action);
        }

        element("timeline").append(row);
    }

    renderActionDetails(vehicleId, action) {
        const row = document.createElement("tr");
        const values = [
            vehicleId,
            `${action.kind} / ${action.phase}`,
            `${action.start}–${action.end}`,
            `${action.origin} → ${action.destination}`,
            `${action.beforeWh} → ${action.afterWh}`,
            action.tripId || action.chargerId || "",
        ];

        for (const value of values) {
            const cell = document.createElement("td");
            cell.textContent = value;
            row.append(cell);
        }

        element("details").append(row);
    }
}

new FleetTimeline();
