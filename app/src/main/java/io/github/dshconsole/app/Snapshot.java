package io.github.dshconsole.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** /progress 接口的数据模型 */
public class Snapshot {

    public long ts;
    public List<Task> tasks = new ArrayList<>();
    public Sys system = new Sys();
    public List<Alert> alerts = new ArrayList<>();
    public List<Msg> messages = new ArrayList<>();
    public List<Daemon> daemons = new ArrayList<>();
    public String rawJson = "";

    public static class Task {
        public String id = "", name = "", state = "idle", detail = "", speed = "", eta = "";
        public float progress = 0f;

        public boolean isActive() {
            return "running".equals(state) || "queued".equals(state);
        }
    }

    public static class Sys {
        public int cpu = 0, cpuCores = 4;
        public String cpuModel = "";
        public float load1 = 0, load5 = 0;
        public int cpuTemp = -1;
        public int mem = 0;
        public String memTotal = "", memUsed = "", memFree = "";
        public String swapTotal = "", swapFree = "";
        public int disk = 0;
        public String diskFree = "";
        public boolean tailscale = false;
        public String uptime = "", host = "";
        // 网络
        public long netRx = 0, netTx = 0;
        public long netRxSpeed = 0, netTxSpeed = 0;
        public String netIface = "";
        // 磁盘 IO
        public long diskRead = 0, diskWrite = 0;
        public long diskReadSpeed = 0, diskWriteSpeed = 0;
        public String diskIface = "";
    }

    public static class Alert {
        public String id = "", level = "info", kind = "", title = "", detail = "";
        public long ts = 0;
    }

    public static class Daemon {
        public String name = "", label = "", active = "", enabled = "", since = "";
        public boolean critical = false;
        public boolean healthy = false;
        public int restarts = 0;
    }

    public static class Msg {
        public String id = "", title = "", body = "", from = "";
        public long ts = 0;
        public boolean read = false;
    }

    public Task activeTask() {
        for (Task t : tasks) if (t.isActive()) return t;
        return null;
    }

    public boolean isBusy() { return activeTask() != null; }

    public static Snapshot parse(String json) throws Exception {
        Snapshot s = new Snapshot();
        s.rawJson = json;
        JSONObject root = new JSONObject(json);
        s.ts = root.optLong("ts", 0);

        JSONArray ta = root.optJSONArray("tasks");
        if (ta != null) {
            for (int i = 0; i < ta.length(); i++) {
                JSONObject o = ta.getJSONObject(i);
                Task t = new Task();
                t.id = o.optString("id", "");
                t.name = o.optString("name", "");
                t.state = o.optString("state", "idle");
                t.detail = o.optString("detail", "");
                t.speed = o.optString("speed", "");
                t.eta = o.optString("eta", "");
                t.progress = (float) o.optDouble("progress", 0);
                s.tasks.add(t);
            }
        }

        JSONObject sys = root.optJSONObject("system");
        if (sys != null) {
            Sys x = s.system;
            x.cpu = sys.optInt("cpu", 0);
            x.cpuCores = sys.optInt("cpuCores", 4);
            x.cpuModel = sys.optString("cpuModel", "");
            x.load1 = (float) sys.optDouble("load1", 0);
            x.load5 = (float) sys.optDouble("load5", 0);
            x.cpuTemp = sys.optInt("cpuTemp", -1);
            x.mem = sys.optInt("mem", 0);
            x.memTotal = sys.optString("memTotal", "");
            x.memUsed = sys.optString("memUsed", "");
            x.memFree = sys.optString("memFree", "");
            x.swapTotal = sys.optString("swapTotal", "");
            x.swapFree = sys.optString("swapFree", "");
            x.disk = sys.optInt("disk", 0);
            x.diskFree = sys.optString("diskFree", "");
            x.tailscale = sys.optBoolean("tailscale", false);
            x.uptime = sys.optString("uptime", "");
            x.host = sys.optString("host", "");
            x.netRx = sys.optLong("netRx", 0);
            x.netTx = sys.optLong("netTx", 0);
            x.netRxSpeed = sys.optLong("netRxSpeed", 0);
            x.netTxSpeed = sys.optLong("netTxSpeed", 0);
            x.netIface = sys.optString("netIface", "");
            x.diskRead = sys.optLong("diskRead", 0);
            x.diskWrite = sys.optLong("diskWrite", 0);
            x.diskReadSpeed = sys.optLong("diskReadSpeed", 0);
            x.diskWriteSpeed = sys.optLong("diskWriteSpeed", 0);
            x.diskIface = sys.optString("diskIface", "");
        }

        JSONArray aa = root.optJSONArray("alerts");
        if (aa != null) {
            for (int i = 0; i < aa.length(); i++) {
                JSONObject o = aa.getJSONObject(i);
                Alert a = new Alert();
                a.id = o.optString("id", "");
                a.level = o.optString("level", "info");
                a.kind = o.optString("kind", "");
                a.title = o.optString("title", "");
                a.detail = o.optString("detail", "");
                a.ts = o.optLong("ts", 0);
                s.alerts.add(a);
            }
        }

        JSONArray da = root.optJSONArray("daemons");
        if (da != null) {
            for (int i = 0; i < da.length(); i++) {
                JSONObject o = da.getJSONObject(i);
                Daemon d = new Daemon();
                d.name = o.optString("name", "");
                d.label = o.optString("label", "");
                d.active = o.optString("active", "");
                d.enabled = o.optString("enabled", "");
                d.since = o.optString("since", "");
                d.critical = o.optBoolean("critical", false);
                d.healthy = o.optBoolean("healthy", false);
                d.restarts = o.optInt("restarts", 0);
                s.daemons.add(d);
            }
        }

        JSONArray ma = root.optJSONArray("messages");
        if (ma != null) {
            for (int i = 0; i < ma.length(); i++) {
                JSONObject o = ma.getJSONObject(i);
                Msg m = new Msg();
                m.id = o.optString("id", "");
                m.title = o.optString("title", "");
                m.body = o.optString("body", "");
                m.from = o.optString("from", "");
                m.ts = o.optLong("ts", 0);
                m.read = o.optBoolean("read", false);
                s.messages.add(m);
            }
        }

        return s;
    }
}
