"""Drive the packaged engine through its real NDJSON protocol with a test-only platform peer."""
import asyncio,datetime,json,sqlite3,sys,tempfile,time,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[1]


class BaselineWebsite:
    def __init__(self, day, cutoff):
        self.first = day - datetime.timedelta(days=9)
        self.end = day
        self.cutoff = cutoff
        self.rows = []
        for offset, count in enumerate([12] * 10):
            date = day - datetime.timedelta(days=offset)
            for second in range(count, 0, -1):
                self.rows.append(f'{date} 10:00:{second:02d}\n开多\n'
                                 '以均价为30000USDT 买入开多BTCUSDT永续合约，成交数量为 0.1BTC，总价值为3000USDT。')
        self.document = None
        self.attempts_at_forty = []
        self.date_commands = []
        self.maximum = 0

    def snapshot(self, request, snapshot):
        if self.document != snapshot['id']:
            self.document = snapshot['id']
            self.count = 10
            self.busy = 0
            self.hide_more = False
        command = request.get('command')
        if command == 'more':
            if self.count == 40 and not self.cutoff:
                self.attempts_at_forty.append(time.monotonic())
                if len(self.attempts_at_forty) == 1:
                    self.busy = 2
                else:
                    self.hide_more = True
            else:
                self.count = min(len(self.rows), self.count + 10)
            self.maximum = max(self.maximum, self.count)
            snapshot.update(phase='more', clicked=True)
        elif command == 'date-pick':
            self.date_commands.append(request['date'])
            snapshot.update(phase='calendar', blocked=True)
        else:
            visible = self.rows[:self.count]
            more = self.count < len(self.rows) and not self.hide_more
            snapshot.update(phase='rows', dates=[self.first.isoformat(), self.end.isoformat()],
                            datePicker=True, text='\n'.join(visible), rowCount=len(visible),
                            more=more, moreReady=more and not self.busy, busy=self.busy > 0)
            self.busy = max(0, self.busy - 1)


class RejoiningWebsite:
    primary = '53100000001'
    other = '53100000002'

    def __init__(self, day):
        self.day = day
        self.new = 0
        self.clicks = 0
        self.views = {}
        self.page_echo = False
        self.date_commands = 0

    def snapshot(self, request, snapshot, url):
        primary = '/' + self.primary + '?' in url
        total = 40 + self.new if primary else 2
        key = (url, snapshot['id'])
        visible = self.views.get(key, 10)
        if request.get('command') == 'date-pick':
            self.date_commands += 1
            snapshot.update(phase='calendar', blocked=True)
            return
        if request.get('command') == 'more':
            if primary:
                self.clicks += 1
            self.views[key] = visible + 10
            snapshot.update(phase='more', clicked=True)
            return
        latest = datetime.datetime.combine(self.day, datetime.time(10, 0))
        timestamps = [latest + datetime.timedelta(minutes=i) for i in range(total, max(0, total - visible), -1)]
        if primary and self.page_echo and latest + datetime.timedelta(minutes=37) in timestamps:
            idx = timestamps.index(latest + datetime.timedelta(minutes=37))
            timestamps.insert(idx, timestamps[idx])
        rows = '\n'.join(f'{stamp}\n开多\n以均价为30000USDT 买入开多BTCUSDT永续合约，成交数量为 0.1BTC，总价值为3000USDT。'
                         for stamp in timestamps)
        snapshot.update(phase='rows', text=rows, rowCount=len(timestamps), more=visible < total,
                        moreReady=visible < total)


class QuantityRejoiningWebsite:
    primary = RejoiningWebsite.primary
    other = RejoiningWebsite.other

    def __init__(self, day):
        def row(clock, action, side, symbol, quantity, price):
            return (f'{day} {clock}\n{action}\n'
                    f'以均价为{price}USDT {side}{action}{symbol}USDT永续合约，成交数量为 {quantity}{symbol}。')
        tail = row('20:12:00', '开多', '买入', 'ETH', '1', '3000')
        earlier_close = row('20:10:00', '平空', '买入', 'SKHYNIX', '2', '90')
        opening = row('20:00:00', '开空', '卖出', 'SKHYNIX', '10', '100')
        older = row('19:59:00', '开多', '买入', 'ETH', '1', '3100')
        close = row('20:14:13', '平空', '买入', 'SKHYNIX', '7', '80')
        new_open = row('20:15:00', '开多', '买入', 'ETH', '1', '3200')
        self.original = [tail, earlier_close, opening, older]
        self.pages = [[new_open, new_open, close, close, tail], [earlier_close], [opening, older]]
        self.current = {}
        self.updated = False
        self.clicks = 0
        self.date_commands = 0

    def snapshot(self, request, snapshot, url):
        command = request.get('command')
        key = (url, snapshot['id'])
        if command == 'date-pick':
            self.date_commands += 1
            snapshot.update(phase='calendar', blocked=True)
            return
        if self.primary not in url:
            rows, more = self.original[:2], False
        elif not self.updated:
            rows, more = self.original, False
        else:
            page = self.current.get(key, 0)
            if command == 'more':
                self.current[key] = page + 1
                self.clicks += 1
                snapshot.update(phase='more', clicked=True)
                return
            rows = [row for batch in self.pages[:page + 1] for row in batch]
            more = page + 1 < len(self.pages)
        snapshot.update(phase='rows', datePicker=True, text='\n'.join(rows),
                        rowCount=len(rows), more=more, moreReady=more)


async def test(pagination=False, cutoff=False, rejoin=False, quantity_review=False):
 with tempfile.TemporaryDirectory() as home:
  java=await asyncio.create_subprocess_exec('java','-XX:-UsePerfData','-Dterminal.home='+home,'-cp',str(root/'build/CopyTerminal/lib/*'),'com.localcopy.terminal.DesktopApp',stdin=-1,stdout=-1)
  docs={};futures={};additional=False;last=[];position_reads=0
  async def write(x):
   java.stdin.write((json.dumps(x)+'\n').encode());await java.stdin.drain()
  async def cmd(op,data=None):
   key=str(uuid.uuid4());future=asyncio.get_running_loop().create_future();futures[key]=future
   await write(dict(id=key,op=op,data=data or {}));res=await asyncio.wait_for(future,30)
   if 'error' in res:raise AssertionError(res['error'])
   return res['data']
  day=datetime.date.today()
  website=(QuantityRejoiningWebsite(day) if quantity_review else RejoiningWebsite(day) if rejoin
           else BaselineWebsite(day, cutoff) if pagination else None)
  short_open=day.isoformat()+' 09:01:00\n开空\n以均价为60000USDT 卖出开空BTCUSDT永续合约，成交数量为 0.2BTC，总价值为12000USDT。'
  short_close=day.isoformat()+' 09:05:00\n平空\n以均价为59000USDT 买入平空BTCUSDT永续合约，成交数量为 0.2BTC，总价值为11800USDT。已实现盈亏为200USDT。'
  closed_position='\n'.join(['BTCUSDT','永续','全仓','做空','全部平仓','开仓时间',
                            day.isoformat()+' 09:01:00','最大持仓量','0.2 BTC','全部平仓',
                            day.isoformat()+' 09:05:00','已平仓量','0.2 BTC'])
  def signals():
   result=[]
   for opening in ([False,True] if additional else [True]):
    action='开多' if opening else '平多';t='10:01:00' if opening else '10:02:00'
    result.append(day.strftime('%Y-%m-%d')+' '+t+'\n'+action+'\n以均价为30000USDT '+('买入开多' if opening else '卖出平多')+'BTCUSDT永续合约，成交数量为 0.1BTC，总价值为3000USDT。'+('' if opening else '已实现盈亏为0USDT。'))
   return '\n'.join(result+[short_close,short_close,short_open,short_open])
  async def reader():
   nonlocal position_reads
   while line:=await java.stdout.readline():
    msg=json.loads(line)
    if 'ui' in msg:futures.pop(msg['ui']).set_result(msg);continue
    if 'host' not in msg:continue
    data=msg['data'];kind=msg['host'];reply={}
    if kind=='web.eval':
     request,_=json.JSONDecoder().raw_decode(data['script'].rsplit('return copyTerminalCapture(',1)[1])
     if data['page'] not in docs:docs[data['page']]=request['document']
     snapshot=dict(id=docs[data['page']],name='桌面测试组合',leaderMargin='10000',dates=[],zone='UTC',more=False,busy=False,loading=False)
     if request['profile']:snapshot.update(phase='profile')
     elif request['balance']:snapshot.update(phase='balance',transfers=[],more=False)
     elif request['positionHistory']:
      position_reads+=1
      snapshot.update(phase='positions',positions=[closed_position])
     elif rejoin or quantity_review:
      website.snapshot(request,snapshot,data['url'])
     elif website is not None:
      website.snapshot(request,snapshot)
     else:snapshot.update(phase='rows',text=signals(),rowCount=6 if additional else 5)
     reply['value']=json.dumps(json.dumps(snapshot,ensure_ascii=False))
    elif kind in ('web.reload','web.load','web.destroy'):docs.pop(data['page'],None)
    try:
     await write({'reply':msg['id'],'data':reply})
    except (BrokenPipeError, ConnectionResetError):
     if java.stdin.is_closing():
      return
     raise
  task=asyncio.create_task(reader())
  async def until(fn,secs=25):
   deadline=asyncio.get_running_loop().time()+secs
   while asyncio.get_running_loop().time()<deadline:
    state=await cmd('state');last[:]=[state]
    if fn(state):return state
    await asyncio.sleep(.25)
   raise AssertionError(str([(k,last[0].get(k)) for k in ['status','capture','captureError','log','leader','balanceStatus']]))
  try:
   await cmd('accept');s=await cmd('state');config=s['config']
   if rejoin or quantity_review:
    async def start_for(portfolio):
     config['portfolio']=portfolio
     await cmd('rules',config)
     await cmd('start',{'mode':'watch'})
    async def stop():
     await cmd('stop')
     await until(lambda s:not s['running'])
    async def archived(total):
     for _ in range(100):
      records=await cmd('history',{'type':'trades'})
      if records['total']==total:
       return
      await asyncio.sleep(.2)
     raise AssertionError(f"history expected {total}, received {records['total']}")

    if quantity_review:
     await start_for(website.primary)
     await archived(4)
     await stop()
     await start_for(website.other)
     await archived(2)
     await stop()
     website.updated = True
     await start_for(website.primary)
     await archived(6)
     state=await until(lambda s:'已衔接旧档案' in s['status'])
     quantities={row[0]: row[2] for row in state['leader']}
     assert quantities == {'ETHUSDT': '3', 'SKHYNIXUSDT': '1'}, quantities
     assert website.clicks == 2, website.clicks
     assert website.date_commands == 0 and position_reads == 0
     await stop()
     with sqlite3.connect(Path(home)/'leader-history.db') as db:
      portfolio=(website.primary,)
      assert db.execute('SELECT COUNT(*) FROM trade_reviews WHERE portfolio=? AND checked IS NULL',
                        portfolio).fetchone()[0] == 0
      assert db.execute("SELECT COUNT(*) FROM trades WHERE portfolio=? AND symbol='SKHYNIXUSDT' "
                        "AND action='CLOSE_SHORT' AND quantity='7' AND excluded_revision=0",
                        portfolio).fetchone()[0] == 1
     print('PASS · two-page close quantity review after old archive join and immediate identical open deduplication')
     return

    await start_for(website.primary)
    await archived(40)
    assert website.clicks==3, website.clicks
    await stop()
    await start_for(website.other)
    await archived(2)
    await stop()
    website.new=3
    website.page_echo=True
    old_time = int(datetime.datetime.combine(day, datetime.time(10, 37, 30),
                                              datetime.timezone.utc).timestamp()*1000)
    with sqlite3.connect(Path(home)/'leader-history.db') as db:
     db.execute('INSERT INTO trade_reviews (portfolio,stamp,sample) VALUES (?,?,?)',
                (website.primary,old_time,'[]'))
    await start_for(website.primary)
    await archived(43)
    state=await until(lambda s:'1 页 · 已核对重合' in s['historyDetail'])
    assert website.clicks==3, website.clicks
    assert state['leader'][0][2]=='4.3', state['leader']
    assert website.date_commands==0 and position_reads==0
    await stop()
    website.page_echo=False

    website.new=15
    await cmd('start',{'mode':'watch'})
    await archived(55)
    state=await until(lambda s:'断档补页 2 页 · 已核对重合' in s['capture'])
    assert website.clicks==4, website.clicks
    assert state['leader'][0][2]=='5.5', state['leader']
    await stop()
    print('PASS · one-page archive return ignores old echo and stale review; restart bridges the gap with one click')
    return
   config['portfolio']='5305355976370339073' if pagination else '12345678901'
   await cmd('rules',config);await cmd('start',{'mode':'watch'})
   await until(lambda s:s['running'] and len(s['leader'])==1,secs=65 if pagination else 25)
   if website is not None:
    records=await cmd('history',{'type':'trades'})
    assert not website.date_commands, website.date_commands
    if cutoff:
     assert records['total']==85 and 90<=website.maximum<120, (records['total'],website.maximum)
    else:
     assert records['total']==40, records['total']
     assert len(website.attempts_at_forty)==2, website.attempts_at_forty
     assert website.attempts_at_forty[1] - website.attempts_at_forty[0]>=4
    assert not (await cmd('state'))['detections']
    await cmd('stop');await until(lambda s:not s['running'])
    print('PASS · seven-day cutoff with More still visible' if cutoff
          else 'PASS · busy retry and stable end at 40, without changing dates')
    return
   assert position_reads==0, position_reads
   assert (await cmd('history',{'type':'trades'}))['total']==3
   additional=True
   await until(lambda s:not s['leader'] and s['detections'])
   assert (await cmd('history',{'type':'trades'}))['total']==4
   await cmd('pause');assert not (await cmd('state'))['opens']
   await cmd('resume');assert (await cmd('state'))['opens']
   await cmd('stop');await until(lambda s:not s['running'])
   print('PASS · release-jar protocol, local exact open/close deduplication without date or position review, polling, pause/resume/stop')
  finally:
   java.stdin.close();await java.wait();await task
asyncio.run(test('--pagination' in sys.argv or '--cutoff' in sys.argv, '--cutoff' in sys.argv,
                 '--rejoin' in sys.argv, '--quantity-review' in sys.argv))
