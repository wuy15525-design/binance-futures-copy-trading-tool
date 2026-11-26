package com.localcopy.terminal;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class DesktopApp extends DesktopContext {
    final LocalData store=new LocalData(this);
    final LeaderArchive archive=LeaderArchive.open(this);
    final LeaderBalances balances=LeaderBalances.open(this);
    final ExecutorService commands=Executors.newSingleThreadExecutor(), views=Executors.newSingleThreadExecutor();
    AutoFollowService service;
    public static void main(String[] args)throws Exception{
        DesktopApp app=new DesktopApp();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            String line;
            while((line=in.readLine())!=null){
                JSONObject m=new JSONObject(line);
                if(m.has("network")){DesktopContext.online=m.getBoolean("network");continue;}
                if(m.has("reply")){Bridge.accept(m);continue;}
                if(m.has("webError")){DesktopPage page=DesktopPage.pages.get(m.optString("webError"));if(page!=null)Handler.lane.execute(()->page.errors.accept(m));continue;}
                String op=m.getString("op");(Set.of("state","history").contains(op)?app.views:app.commands).execute(()->{
                    JSONObject result=new JSONObject().put("ui",m.getString("id"));
                    try{result.put("data",app.handle(op,m.optJSONObject("data")==null?new JSONObject():m.getJSONObject("data")));}
                    catch(Exception e){result.put("error",e.getMessage()==null?"操作失败":e.getMessage());}
                    Bridge.send(result);
                });
            }
        }finally{if(app.service!=null)app.service.onDestroy();System.exit(0);}
    }
    void command(String action,JSONObject d){if(service==null||service.disposed){service=new AutoFollowService();service.onCreate();}service.onStartCommand(new Intent(action,d),0,1);}
    boolean busy(){return service!=null&&!service.disposed||AutoFollowService.running()||prefs.contains("auto_active");}
    JSONObject handle(String op,JSONObject d)throws Exception{
        Rules.Config c=store.config();String pf=c.portfolio;
        switch(op){
          case "state":return state(c,d.optLong("detectionPage",1));
          case "history":return history(pf,d);
          case "introSeen":prefs.edit().putString("intro_version","4.2.9").apply();break;
          case "accept":prefs.edit().putBoolean("accepted",true).apply();break;
          case "restore":if(prefs.contains("auto_active") && (service==null||service.disposed))command(AutoFollowService.RESTORE,d);break;
          case "rules":{
            Rules.Config updated=LocalData.readConfig(d);
            boolean switchId=!updated.portfolio.equals(pf);
            if(switchId&&busy())throw new IllegalStateException("请先停止跟单，再切换组合");
            if(switchId||(!busy()&&archive.state(pf).name.isEmpty())){
              WebsiteCapture lookup=new WebsiteCapture(this,updated.portfolio);
              try{archive.saveName(updated.portfolio,lookup.name());}finally{lookup.close();}
            }
            DemoOrders.checkWhitelist(store,updated.symbols);AutoFollowService.saveRules(store,archive,updated);break;
          }
          case "symbols":return new JSONObject().put("items",new JSONArray(new FuturesApi(FuturesApi.LIVE,null).tradableSymbols()));
          case "credentials":{
            if(busy())throw new IllegalStateException("停止跟单后可修改账户");
            String realm=realm(d);Secrets value=new Secrets(d.getString("key"),d.getString("secret"));
            boolean held=realm.equals("demo")?(DemoOrders.hasWork(store)||AutoFollowService.hasExposure(store,AutoFollowService.DRILL)||AutoFollowService.hasExposure(store,AutoFollowService.DEMO_WEB)):realm.equals("live")&&AutoFollowService.hasExposure(store,AutoFollowService.REAL);
            if(held&&!Secrets.load(store,realm).key.equals(value.key))throw new IllegalStateException("有仓位或委托待核对，请保留原账户");
            Secrets.save(store,realm,value);break;
          }
          case "account":{
            if(busy())throw new IllegalStateException("运行中请查看运行页的实时账户");
            String realm=realm(d),mode=realm.equals("demo")?"demo":"live";
            String saved=mode.equals("live")&&prefs.contains("credentials_readonly")?"readonly":mode;
            FuturesApi api=new FuturesApi(mode.equals("demo")?"demo":"readonly",Secrets.load(store,saved));
            AutoFollowService.publishWallet(mode,api.wallet());AutoFollowService.publishPositions(mode,api.positions());
            AutoFollowService.publishLeverages(mode,api.symbolLeverages());break;
          }
          case "start":{
            if(busy())throw new IllegalStateException("另一个跟单正在运行或结束中");
            if(!prefs.getBoolean("accepted",false))throw new IllegalStateException("请先阅读使用提示");
            String mode=d.getString("mode");if(!AutoFollowService.validMode(mode))throw new IllegalArgumentException("模式无效");
            if(mode.equals(AutoFollowService.REAL)&&!d.optBoolean("liveConfirmed"))throw new IllegalArgumentException("请确认当前为实盘跟单");
            if(!mode.equals(AutoFollowService.WATCH))Secrets.load(store,mode.equals(AutoFollowService.REAL)?"live":"demo");
            Rules.Strategy strategy=Rules.Strategy.read(d.optString("strategy"));
            FollowBook old=AutoFollowService.savedFollowBook(store,mode,c);
            if(old!=null&&old.hasWork()&&old.strategy!=strategy)throw new IllegalStateException("有跟单仓位或待核对委托，请使用原策略");
            if(strategy==Rules.Strategy.EXPOSURE)Rules.copyFactor(d.getString("copy-factor"));
            if(AutoFollowService.followsWebsite(mode)&&d.has("copy-slippage"))Rules.adjustmentSlippage(d.getString("copy-slippage"));
            command(mode,d);break;
          }
          case "stop":case "pause":case "resume":case "retry-web":case "initial-choice":case "copy-factor":
            if(service==null||service.disposed)throw new IllegalStateException("当前没有运行中的任务");
            command("follow-"+op,d);break;
          case "region":if(!WebsiteCapture.showWebsite(prefs.getBoolean("auto_baseline_waiting",false)))
                throw new IllegalStateException("当前没有等待处理的官网页面");break;
          case "cancel":AutoFollowService.cancelWait(store,d.getString("mode"),d.getString("id"));break;
          case "export":{
            java.nio.file.Path tmp=java.nio.file.Files.createTempFile(home,"history-",".csv");
            try{try(OutputStream out=java.nio.file.Files.newOutputStream(tmp)){archive.export(pf,out);}
                return Bridge.call("saveCsv",new JSONObject().put("temp",tmp.getFileName().toString()).put("portfolio",pf));
            }finally{java.nio.file.Files.deleteIfExists(tmp);}
          }
          case "sound":return Bridge.call("notice",new JSONObject().put("title","合约跟单台").put("message","Windows 提示音测试").put("sound",true));
          case "quit":
            if(busy()&&service!=null&&!service.disposed){command(AutoFollowService.STOP,d);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!service.disposed&&System.nanoTime()<deadline)Thread.sleep(80);
              if(!service.disposed)throw new IllegalStateException("正在等待本轮请求结束，请稍后退出");}break;
          default:throw new IllegalArgumentException("未知操作");
        }
        return new JSONObject().put("ok",true);
    }
    static String realm(JSONObject d){String s=d.getString("realm");if(!Set.of("live","readonly","demo").contains(s))throw new IllegalArgumentException("账户类型无效");return s;}
    static String price(BigDecimal v){return v==null?"—":TradeNotice.positionPrice(v);}
    static String money(BigDecimal v){return v==null?"—":TradeNotice.positionMoney(v);}
    static JSONArray reverse(JSONArray a){JSONArray out=new JSONArray();for(int i=a.length()-1;i>=0;i--)out.put(a.get(i));return out;}
    static JSONArray row(Object...cells){return new JSONArray(Arrays.asList(cells));}
    JSONObject state(Rules.Config c) throws Exception { return state(c, 1); }

    JSONObject state(Rules.Config c, long detectionPage)throws Exception{
        String pf=c.portfolio;LeaderArchive.State a=archive.state(pf);
        String mode=prefs.getString("auto_active",prefs.getString("auto_last_mode",""));
        boolean match=pf.equals(prefs.getString("auto_portfolio",""));
        FollowBook b=match&&AutoFollowService.validMode(mode)?AutoFollowService.savedFollowBook(store,mode,c):null;
        var wallet=AutoFollowService.walletSnapshot();var positions=AutoFollowService.accountPositions();long now=System.currentTimeMillis();
        String realm=AutoFollowService.running()?(mode.equals(AutoFollowService.REAL)||mode.equals(AutoFollowService.WATCH)?"live":"demo"):wallet.realm;
        boolean acct=realm.equals(positions.realm),fresh=acct&&positions.time>0&&now-positions.time<20000&&positions.error.isEmpty();
        var balance=store.leaderBalance(pf);JSONObject out=new JSONObject().put("config",LocalData.configJson(c)).put("name",a.name).put("mode",mode)
            .put("showIntro",!"4.2.9".equals(prefs.getString("intro_version",""))).put("running",AutoFollowService.running()).put("accepted",prefs.getBoolean("accepted",false)).put("opens",prefs.getBoolean("auto_opens",true))
            .put("status",match?prefs.getString("auto_status","等待开始"):"等待开始").put("capture",match?prefs.getString("auto_capture",""):"")
            .put("captureError",match?prefs.getString("auto_capture_error",""):"").put("access",match?prefs.getString("auto_web_access",""):"")
            .put("baselineWaiting",match&&prefs.getBoolean("auto_baseline_waiting",false))
            .put("accountStatus",acct?positions.error:"").put("accountAt",acct?positions.time:0).put("walletAt",realm.equals(wallet.realm)?wallet.time:0)
            .put("walletError",realm.equals(wallet.realm)?wallet.error:"").put("wallet",realm.equals(wallet.realm)&&wallet.balance!=null?money(wallet.balance.balance):"—")
            .put("pnl",b==null?"—":money(b.totalPnl(fresh?positions.positions:Map.of()))).put("duration",b==null?0:b.trackingMillis(now))
            .put("margin",balance==null?"—":money(balance.value)).put("marginAt",balance==null?0:balance.at)
            .put("balanceStatus",prefs.getString("balance_error_"+pf,"")).put("historyDetail",a.historyDetail)
            .put("strategy",b==null?"POINTS":b.strategy.name()).put("factor",b==null?"1":Rules.show(b.copyFactor)).put("slip",b==null?"0.3":Rules.show(b.copySlippage))
            .put("copyReplanPending",b!=null&&b.copyReplanPending);
        JSONArray portfolios=new JSONArray(),keys=new JSONArray(),leader=new JSONArray(),mine=new JSONArray(),waiting=new JSONArray();
        for(var record:archive.followedPortfolios())portfolios.put(row(record.id,record.name));
        for(String key:List.of("live","readonly","demo"))if(prefs.contains("credentials_"+key))keys.put(key);
        var market=AutoFollowService.leaderMarket();var projection=archive.projection(pf,a);
        if(balance==null){for(int i=a.window.size()-1;i>=0;i--){var previous=a.window.get(i).leaderMargin;if(previous!=null&&previous.signum()>0){balance=new LocalData.LeaderBalance(previous,0);break;}}}
        var risk=LeaderRisk.estimate(projection.holdings,balance==null?null:balance.value,market.canUseMarks(now,balance)?market.marks:Map.of(),market.tiers);
        out.put("riskNote",Display.leaderRiskDescription(a,projection,balance,market,risk,now));
        for(var item:new TreeMap<>(projection.holdings).entrySet()){
            var h=item.getValue();String[] leg=item.getKey().split("\\|",2);boolean buy="LONG".equals(leg[1]);
            if(h.displayQuantity().signum()>0)leader.put(row(leg[0],buy?"多":"空",(h.inferredQuantity==null?"":"≈ ")+TradeNotice.positionQuantity(h.displayQuantity()),price(h.entryPrice),Display.forecastText(risk.prices.get(leg[0])),money(h.unrealized(market.hasLatestPrices(now)?market.latestPrices.get(leg[0]):null,buy)),TradeNotice.fullTime(h.updatedAt)));
        }
        var leverage=AutoFollowService.accountLeverages(positions.realm);
        if(acct)for(var item:new TreeMap<>(positions.positions).entrySet()){
            var p=item.getValue();for(boolean buy:new boolean[]{true,false}){
                BigDecimal n=buy?p.longs:p.shorts;if(n.signum()<=0)continue;var r=buy?p.longRisk:p.shortRisk;
                mine.put(row(item.getKey(),buy?"多":"空",TradeNotice.positionQuantity(n),price(buy?p.longEntry:p.shortEntry),money(r==null?null:r.initialMargin),price(r==null||r.liquidationPrice==null||r.liquidationPrice.signum()==0?null:r.liquidationPrice),leverage.get(item.getKey()),money(buy?p.longUnrealized:p.shortUnrealized),TradeNotice.fullTime(buy?p.longUpdated:p.shortUpdated)));
            }
        }
        if(b!=null){
            var cancelled=store.waitCancellations(mode);var prices=AutoFollowService.waitingPrices();
            for (var entry : b.waits()) {
                if (cancelled.contains(entry.id())) continue;
                var rule = c.rules.getOrDefault(entry.signal.symbol, b.config.rule(entry.signal.symbol));
                var leaderAverage = b.leg(entry.signal.symbol, entry.signal.action.isLong).leaderAverage;
                BigDecimal slippage = entry.usesAdjustmentSlippage() ? b.copySlippage : rule.slippagePercent;
                String description = Display.waitDescription(entry, mode, prices, slippage, leaderAverage);
                String status = Display.waitRequestStatus(entry, b.pending, AutoFollowService.running(),
                        out.getBoolean("opens"), out.optString("captureError").isEmpty(), now);
                waiting.put(row(entry.signal.symbol, description + "\n" + status, entry.id()));
            }
            for (var leg : b.legs.values()) {
                if (leg.adjustment != null && !cancelled.contains(leg.adjustment.id())) {
                    var adjustment = leg.adjustment;
                    String reason = adjustment.waitReason;
                    if (b.pending != null && adjustment.id().equals(b.pending.optString("copyAdjustment"))) {
                        reason = "原委托号待核对";
                    } else if (!AutoFollowService.running()) reason = "等待恢复跟单";
                    else if (b.pending != null) reason = "等待在途委托核对完成";
                    else if (!out.optString("captureError").isEmpty()) reason = "等待官网成交核对完成";
                    BigDecimal slippage = b.copySlippage;
                    if (!leg.adjustmentUsesCopySlippage() && leg.leaderAverage != null) {
                        slippage = slippage.min(c.rules.getOrDefault(leg.symbol, b.config.rule(leg.symbol)).slippagePercent);
                    }
                    String display = Display.copyWaitDescription(leg, slippage, mode, prices);
                    waiting.put(row(leg.symbol, reason.isEmpty() ? display : display + "\n状态 · " + reason,
                            adjustment.id()));
                }
            }
        }
        var init=AutoFollowService.initialPositions();if(init!=null&&init.portfolio.equals(pf)){
            JSONArray options=new JSONArray();for(var s:init.candidates)options.put(row(s.id,s.symbol,s.action.label,Rules.show(s.quantity),price(s.price)));
            out.put("initial",new JSONObject().put("portfolio",pf).put("head",Long.toString(init.head)).put("backfilled",Long.toString(init.backfilled)).put("options",options).put("canAdd",init.canAdd()).put("awaitingReview",init.awaitingReview));
        }
        final int detectionPageSize = 8;
        boolean drill = AutoFollowService.DRILL.equals(mode);
        JSONArray drillEvents = drill && b != null ? reverse(b.detections) : new JSONArray();
        long total = drill ? drillEvents.length() : archive.detectionCount(pf);
        long page = Math.min(Math.max(1, detectionPage), Math.max(1, (total + detectionPageSize - 1) / detectionPageSize));
        long offset = (page - 1) * detectionPageSize;
        JSONArray detections = new JSONArray();
        if (drill) {
            for (int i = (int) offset; i < Math.min(total, offset + detectionPageSize); i++) detections.put(drillEvents.get(i));
        } else {
            for (LeaderArchive.Record row : archive.detections(pf, detectionPageSize, offset)) {
                detections.put(row.detectionText(c.symbols.contains(row.signal.symbol)));
            }
        }
        out.put("detections",detections).put("detectionTotal",total).put("detectionPage",page).put("detectionPageSize",detectionPageSize);
        return out.put("portfolios",portfolios).put("keys",keys).put("leader",leader).put("mine",mine).put("wait",waiting)
            .put("log",b==null?new JSONArray():reverse(b.log))
            .put("pending",b!=null&&b.pending!=null?b.pending.optString("symbol")+" · "+b.pending.optString("id"):"");
    }
    JSONObject history(String pf,JSONObject d){
        String type=d.getString("type");int size=25;long total=switch(type){case "trades"->archive.count(pf);case "fills"->archive.accountFillCount(pf);case "transfers"->balances.count(pf);default->throw new IllegalArgumentException("记录类型无效");};
        long page=Math.max(1,Math.min(d.optLong("page",1),Math.max(1,(total+size-1)/size)));long offset=(page-1)*size;JSONArray rows=new JSONArray();
        if(type.equals("trades"))for(var r:archive.page(pf,size,offset)){var s=r.signal;rows.put(row(TradeNotice.fullTime(s.time),s.symbol,s.action.label,Rules.show(s.quantity),price(s.price),money(s.turnover),money(s.realizedPnl),r.detectedAt==0?"—":TradeNotice.fullTime(r.detectedAt)));}
        if(type.equals("fills"))for(var r:archive.accountFills(pf,size,offset))rows.put(row(TradeNotice.fullTime(r.time),AutoFollowService.label(r.mode),r.summary));
        if(type.equals("transfers"))for(var r:balances.page(pf,size,offset))rows.put(row(TradeNotice.fullTime(r.at),r.asset,Rules.show(r.amount),r.from,r.to));
        return new JSONObject().put("rows",rows).put("page",page).put("size",size).put("total",total);
    }
}
