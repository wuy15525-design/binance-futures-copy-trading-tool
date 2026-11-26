package com.localcopy.terminal;
import java.math.BigDecimal;
import java.util.*;
import java.io.*;
import org.json.*;
public class DesktopArchiveTest {
    static final long when=1795050000000L;
    static Rules.Signal signal(int n,Rules.Action a,String q){return new Rules.Signal("BTCUSDT",a,when+n*1000L,new BigDecimal("30000"),new BigDecimal(q));}
    static void check(boolean v,String msg){if(!v)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception{
        DesktopApp app=new DesktopApp();String id=LocalData.DEFAULT_PORTFOLIO;
        LeaderArchive archive=app.archive;
        if(args.length>0){
            check(archive.count(id)==3,"重启历史");
            check(archive.accountFillCount(id)==1,"重启账户成交");
            check(new LocalData(app).prefs.getBoolean("accepted",false),"持久设置");
            check(app.state(app.store.config()).getJSONArray("leader").isEmpty(),"平仓后净仓位");
            System.out.println("PASS · restart restored archive, fill, settings and flat position");return;
        }
        var o=signal(0,Rules.Action.OPEN_LONG,"0.1");var add=signal(1,Rules.Action.OPEN_LONG,"0.2");var close=signal(2,Rules.Action.CLOSE_LONG,"0.3");
        archive.capture(id,"测试组合",List.of(o,add),when+10000,true,"基线");
        archive.capture(id,"测试组合",List.of(o,add),when+11000,false,"");
        check(archive.count(id)==2,"基线重复");check(app.state(app.store.config()).getJSONArray("leader").length()==1,"推算仓位");
        archive.capture(id,"测试组合",List.of(add,close),when+12000,false,"");
        check(archive.count(id)==3,"增量合并");check(app.state(app.store.config()).getJSONArray("leader").isEmpty(),"平仓");
        archive.saveAccountFill(id,"testnet-web","fill001",when,"测试成交");archive.saveAccountFill(id,"testnet-web","fill001",when,"测试成交");
        check(archive.accountFillCount(id)==1,"成交重复");
        app.handle("accept",new JSONObject());check(app.history(id,new JSONObject().put("type","trades")).getLong("total")==3,"历史分页");
        ByteArrayOutputStream csv=new ByteArrayOutputStream();archive.export(id,csv);check(csv.toString("UTF-8").contains("BTCUSDT"),"CSV");
        var db=archive.getWritableDatabase();db.beginTransaction();try{db.execSQL("DELETE FROM trades");}finally{db.endTransaction();}
        check(archive.count(id)==3,"事务回滚");
        try{app.handle("start",new JSONObject().put("mode","mainnet-web"));throw new AssertionError("实盘保护");}catch(IllegalArgumentException ok){check(ok.getMessage().contains("实盘"),"实盘确认原因");}
        detectDuringAccountWait(app);
        System.out.println("PASS · baseline, overlap, close, transaction rollback, fills, paging, CSV and live confirmation");
    }

    private static void detectDuringAccountWait(DesktopApp app) throws Exception {
        String portfolio = "111122223333";
        LeaderArchive archive = app.archive;
        List<Rules.Signal> rows = new ArrayList<>();
        for (int i = 0; i <= 73; i++) rows.add(signal(i, Rules.Action.OPEN_LONG, "0.1"));
        archive.capture(portfolio, "测试", rows.subList(0, 2), when + 10, true, "初始基线");
        check(archive.detectionCount(portfolio) == 0, "初始基线不算新增");
        long detected = when + 200_000;
        archive.observeGap(portfolio, "", rows.subList(70, 74), detected);
        check(archive.state(portfolio).head == 2 && archive.detectionCount(portfolio) == 4,
                "断档观察立即显示，跟单档案水位保持");
        List<LeaderArchive.Record> observed = archive.detections(portfolio, 8, 0);
        check(observed.get(0).detectedAt == detected && observed.get(0).kind == -2,
                "待核对和首次检测时间");
        archive.capture(portfolio, "", rows, detected + 1000, false, "", true);
        check(archive.detectionCount(portfolio) == 72 && archive.detections(portfolio, 8, 64).size() == 8,
                "补齐后超过 60 条的操作仍可翻页");
        var record = archive.detections(portfolio, 80, 0).stream()
                .filter(row -> row.signal.time == rows.get(73).time).findFirst().orElseThrow();
        check(record.detectedAt == detected && record.kind == 0, "观察转正式仅保留一条及首次检测时间");
        archive.capture(portfolio, "", rows, detected + 2000, false, "", true);
        check(archive.detectionCount(portfolio) == 72, "重复快照不重复新增");
        check(archive.detectionCount("87654321") == 0, "组合隔离");
        System.out.println("PASS · detection owns full archive pages, includes unresolved gaps, keeps first time after promotion");
    }
}
