"""Exercise the shipping UI, IPC and Java engine in Chromium using an offline official-page fixture."""
import asyncio,datetime,json,os,sys,tempfile
from pathlib import Path
from playwright.async_api import async_playwright
project=Path(__file__).resolve().parents[1]
stage=project/'build/CopyTerminal'
async def run():
 with tempfile.TemporaryDirectory() as home:
  proc=await asyncio.create_subprocess_exec('java','-XX:-UsePerfData','-Dterminal.home='+home,'-cp',str(stage/'lib/*'),'com.localcopy.terminal.DesktopApp',stdin=-1,stdout=-1,stderr=-1)
  async def send(msg):
   proc.stdin.write((json.dumps(msg,ensure_ascii=False)+'\n').encode());await proc.stdin.drain()
  async with async_playwright() as pw:
   ctx=await pw.chromium.launch_persistent_context(str(Path(home)/'browser'),viewport={'width':1440,'height':900},executable_path=os.getenv('CHROMIUM','/usr/bin/chromium'),args=['--no-sandbox','--disable-gpu','--no-zygote','--single-process','--disable-dev-shm-usage'])
   browser=ctx
   desktop=await ctx.new_page();pages={};failures=[];cases=[];latest=[0];stopped=False
   async def local(route):
    fname=route.request.url.split('/')[-1] or 'index.html'
    if fname not in {'index.html','app.js','style.css','coin.png','intro.ttf'}:return await route.abort()
    await route.fulfill(path=stage/'ui'/fname)
   await desktop.route('https://terminal.localhost/**',local)
   await desktop.expose_function('nativeWrite',send)
   await desktop.add_init_script('window.chrome=window.chrome||{};window.chrome.webview={addEventListener:(k,f)=>window.inbox=f,postMessage:msg=>window.nativeWrite(msg)}')
   async def until(expr,timeout=30000):
    deadline=asyncio.get_running_loop().time()+timeout/1000
    while asyncio.get_running_loop().time()<deadline:
     if await desktop.evaluate(expr):return
     await asyncio.sleep(.15)
    raise AssertionError('Timed out: '+expr)
   desktop.on('pageerror' ,lambda e:failures.append(str(e)))
   now=datetime.datetime.now(datetime.timezone.utc)
   def sample_html():
    rows=[]
    for n in range(latest[0]+1):
     stamp=now-datetime.timedelta(minutes=5-n)
     side='开多' if n==0 else '平多'
     order='买入开多' if n==0 else '卖出平多'
     pnl='' if n==0 else '已实现盈亏为1USDT。'
     rows.append('<div class="item"><div>'+stamp.strftime('%Y-%m-%d %H:%M:%S')+'</div><div>'+side+'</div><div>以均价为30000USDT '+order+'BTCUSDT永续合约，成交数量为 0.1BTC，总价值为3000USDT。'+pnl+'</div></div>')
    return '<h1>桌面回归测试组合</h1><div>带单余额(USDT)</div><strong>5000</strong><button role="tab" aria-selected="true" aria-controls="trades">最新交易记录</button><button role="tab" aria-selected="true" aria-controls="balance">余额历史</button><div id="trades"><div class="LatestRecords"><div class="timeline">'+''.join(reversed(rows))+'</div></div></div><div id="balance">暂无记录</div>'
   async def receive():
    while not proc.stdout.at_eof():
     buf=await proc.stdout.readline()
     if not buf:break
     m=json.loads(buf)
     if 'ui' in m:
      await desktop.evaluate('m=>window.inbox?.({data:m})',m)
     elif 'host' in m:
      op=m['host'];d=m['data'];reply={'reply':m['id'],'data':{}}
      try:
       if op=='web.create':
        w=await ctx.new_page();pages[d['page']]=w
        await w.route('**/*',lambda r:r.fulfill(body=sample_html(),content_type='text/html; charset=utf-8'))
       elif op=='web.load':await pages[d['page']].goto(d['url'])
       elif op=='web.reload':await pages[d['page']].reload()
       elif op=='web.eval':
        value=await pages[d['page']].evaluate(d['script'])
        reply['data']={'value':json.dumps(value,ensure_ascii=False)}
       elif op=='web.destroy':await pages.pop(d['page']).close()
       elif op=='web.stop':pass
       else:raise RuntimeError('unexpected platform call '+op)
      except Exception as e:reply['error']=str(e)
      await send(reply)
   worker=asyncio.create_task(receive())
   try:
    await desktop.goto('https://terminal.localhost/index.html')
    await desktop.get_by_text('我知道了',exact=True).click(timeout=14000)
    await desktop.get_by_role('button',name='跟单设置').click()
    for symbol in ['SKHYNIXUSDT','测试合约USDT']:
     await desktop.locator('#addRule').click()
     await desktop.get_by_label('合约代码').fill(symbol)
     await desktop.get_by_role('button',name='应用',exact=True).click()
    await desktop.locator('#saveRules').click()
    await until("['SKHYNIXUSDT','测试合约USDT'].every(symbol=>state?.config.contractRules.some(r=>r.symbol===symbol))",
                timeout=22000);cases.append('保存股票及中文合约白名单')
    await desktop.get_by_role('button',name='运行总览').click()
    await desktop.locator('#start').click();await desktop.get_by_role('button',name='继续',exact=True).click()
    await until("state?.running && state?.leader.length===1",timeout=35000)
    cases.append('基线读取、推算持仓')
    client=await ctx.new_cdp_session(desktop)
    for width,height,scale in [(1920,1080,1),(1366,768,1),(1024,683,1.5),(768,576,1.75),(620,460,1),(480,360,2)]:
     await client.send('Emulation.setDeviceMetricsOverride',dict(width=width,height=height,deviceScaleFactor=scale,mobile=False));await desktop.wait_for_timeout(70)
     for tab in ['run','history','settings']:
      await desktop.locator('[data-tab="'+tab+'"]').click()
      assert await desktop.evaluate('document.documentElement.scrollWidth <= innerWidth'),(width,tab)
     cases.append('布局 '+str(width)+'×'+str(height)+' @ '+str(scale))
    await client.send('Emulation.clearDeviceMetricsOverride');await desktop.set_viewport_size({'width':1440,'height':900});await desktop.locator('[data-tab="run"]').click()
    pager_at_bottom = """[...document.querySelectorAll('#run .pager')].every(pager => {
      const card = pager.parentElement.getBoundingClientRect();
      const footer = pager.getBoundingClientRect();
      return Math.abs(card.bottom - footer.bottom) <= 2;
    })"""
    assert await desktop.evaluate(pager_at_bottom)
    before = await desktop.locator('#leaderPanel .scroll').bounding_box()
    initial_foot = await desktop.locator('#leaderPanel .pager').bounding_box()
    await desktop.evaluate("document.getElementById('workspace').scrollTop = 100000")
    await desktop.mouse.move(900, 840)
    await desktop.mouse.wheel(0, 2500)
    await desktop.wait_for_timeout(175)
    assert await desktop.evaluate("""() => {
      const scroll = document.getElementById('workspace');
      return scroll.scrollTop > 0 && scroll.scrollTop <= scroll.scrollHeight - scroll.clientHeight + 1
             && window.scrollY === 0 && document.getElementById('shell').getBoundingClientRect().top === 0;
    }""")
    cases.append('主页滚动限位与分页按钮贴底')
    await desktop.evaluate("document.getElementById('workspace').scrollTop = 0")
    await desktop.screenshot(path=str(project/'build/desktop-screen.png'))
    latest[0]=1
    await until("state?.leader.length===0 && state?.detections.length>0",timeout=25000)
    assert await desktop.evaluate(pager_at_bottom)
    after = await desktop.locator('#leaderPanel .scroll').bounding_box()
    after_foot = await desktop.locator('#leaderPanel .pager').bounding_box()
    assert abs(before['height'] - after['height']) <= 1
    assert abs((initial_foot['y'] - before['y']) - (after_foot['y'] - after['y'])) <= 2

    await desktop.locator('[data-tab="history"]').click()
    await desktop.locator('#historyPanel td').filter(has_text='平多').first.wait_for(timeout=12000);cases.append('下一轮新增平仓、入档和成交表')
    panel_heights = {}
    for height in (530, 900):
     await desktop.set_viewport_size({'width':1440,'height':height})
     for tab, title in (('trades','带单员成交'),('fills','我的成交'),('transfers','带单保证金划转')):
      await desktop.locator(f'[data-hist="{tab}"]').click()
      await desktop.locator('#historyPanel h2').filter(has_text=title).wait_for(timeout=10000)
      geometry = await desktop.evaluate("""() => {
        const workspace = document.getElementById('workspace');
        const panel = document.getElementById('historyPanel');
        const table = panel.querySelector('.scroll').getBoundingClientRect();
        const pager = panel.querySelector('.pager').getBoundingClientRect();
        const available = workspace.getBoundingClientRect().bottom - parseFloat(getComputedStyle(workspace).paddingBottom);
        return {height: table.height, tableGap: pager.top - table.bottom,
                footerGap: panel.getBoundingClientRect().bottom - pager.bottom,
                sectionGap: available - document.getElementById('history').getBoundingClientRect().bottom};
      }""")
      assert geometry['height'] > 100 and all(abs(geometry[k]) <= 2 for k in ('tableGap','footerGap','sectionGap')), geometry
      if height == 530: panel_heights[tab] = geometry['height']
      else: assert geometry['height'] - panel_heights[tab] >= 350, geometry
    cases.append('成交记录三个标签页随窗口增高，含空表与固定底部翻页')
    await desktop.locator('[data-tab="run"]').click();await desktop.locator('#pause').click()
    await until("state?.opens===false");await desktop.locator('#pause').click()
    await desktop.locator('#stop').click();await desktop.locator('#dialogActions').get_by_role('button',name='停止运行').click()
    await until("state?.running===false");cases.append('暂停、恢复和停止')
    assert not failures,failures
    print('PASS · '+' / '.join(cases),flush=True)
   finally:
    if sys.exc_info()[0]:
     print('STATE',str(await desktop.evaluate('typeof state!=="undefined" ? state : null'))[:4000],flush=True)
    proc.stdin.close();await proc.wait();worker.cancel()
    await browser.close()
asyncio.run(run())
