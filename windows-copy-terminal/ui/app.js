const legalText=["地区限制与使用资格 · 使用者应自行核对所在地及其他适用司法辖区的法律法规、监管要求和交易平台条款。若加密货币交易、合约交易或自动跟单在适用地区受到禁止或限制，或使用者不具备资格，请停止使用相应功能。不得使用本工具绕过地区限制、身份验证或其他平台访问限制。", "交易风险与损失承担 · 本工具提供公开记录查看与用户自主配置的自动委托功能。页面信息和交易信号不构成投资建议、交易邀约或收益承诺。行情波动、杠杆、强平、滑点、流动性不足、数据延迟、网络中断及交易所或软件异常均可能造成损失，包括全部投入资金。使用者应自行决定是否跟单，并核对账户、参数及委托。在适用法律允许的范围内，作者不承担使用者自行交易或跟单所产生的损失。", "带单员授权与第三方权利 · 公开可见的组合记录仅表明信息可被查看。场外跟单涉及的使用许可、授权和平台规则应由使用者自行核实。本工具与币安及所展示的带单员无官方合作或背书关系。依法或依平台条款需要同意或授权时，使用者应事先取得授权。未经所需授权擅自复制交易、再发布信息或侵害他人权益所产生的纠纷及责任，由使用者依法承担。作者在适用法律允许的范围内不承担此类行为所产生的责任。", "适用范围 · 本声明以适用法律允许的范围为限。依法不得免除或限制的责任及使用者的法定权利，均以适用法律为准。"];
'use strict';
const $=id=>document.getElementById(id);
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n};
const modeNames={'watch':'官网监测','testnet-drill':'测试网自动演练','testnet-web':'测试网跟单','mainnet-web':'实盘跟单'};
const requests=new Map(),pageNos={},fingerprints={};
let state=null,view='run',hist='trades',histPage=1,historySerial=0,showMoney=false,busyPoll=false,draft=null,draftKey='',dirty=false,toastTimer,first=true,historyAt=0,requestedDetectionPage=1;
function rpc(op,data={}){if(!window.chrome?.webview)return Promise.reject(Error('桌面引擎未连接，请从 CopyTerminal.exe 启动'));return new Promise((resolve,reject)=>{const id=crypto.randomUUID();requests.set(id,{resolve,reject});window.chrome.webview.postMessage({id,op,data})})}
window.chrome?.webview?.addEventListener('message',event=>{const msg=event.data,req=requests.get(msg.ui);if(!req)return;requests.delete(msg.ui);msg.error?req.reject(Error(msg.error)):req.resolve(msg.data)});
function toast(s){if($('dialog').open){let err=$('dialogFeedback');if(!err){err=el('p',null,'dialogfeedback');err.id='dialogFeedback';err.setAttribute('role','status');$('dialogBody').append(err)}err.textContent=s;return}$('message').textContent=s;$('message').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('message').hidden=true,6500)}
function action(fn){return async event=>{try{await fn(event)}catch(e){toast(e.message)}}}
function click(id,fn){$(id).addEventListener('click',action(fn))}
function button(text,fn,cls){const b=el('button',text,cls);b.type='button';b.onclick=action(fn);return b}
function set(id,text){const n=$(id);if(n.textContent!==String(text))n.textContent=text}
function visible(id,yes){$(id).hidden=!yes}
function dated(n){return n?new Date(n).toLocaleString('zh-CN',{hour12:false}):'—'}
function duration(ms){const n=Math.max(0,Math.floor(ms/1000));return [Math.floor(n/3600),Math.floor(n/60)%60,n%60].map(x=>String(x).padStart(2,'0')).join(':')}
function formField(caption,type,value,opts){const lab=el('label',caption);const input=el(type==='select'?'select':'input');if(type==='select'){for(const [v,s] of opts){const opt=el('option',s);opt.value=v;input.append(opt)}}else{input.type=type;input.autocomplete='off'}input.value=value??'';lab.append(input);return [lab,input]}
function modal(title,nodes,buttons=[],mandatory=false){const d=$('dialog');if(d.open)d.close();set('dialogTitle',title);$('dialogBody').replaceChildren(...nodes);$('dialogActions').replaceChildren(...buttons);$('closeDialog').hidden=mandatory;d.oncancel=e=>{if(mandatory)e.preventDefault()};d.showModal()}
const close=()=>$('dialog').close();
function confirmButton(label,fn){const b=button(label,async()=>{b.disabled=true;try{await fn()}finally{b.disabled=false}},'primary');return b}
click('closeDialog',close);$('dialogForm').onsubmit=e=>e.preventDefault();
function tablePanel(id,title,headers,all,opts={}){
  const size=opts.size||8,total=opts.total??all.length,maximum=Math.max(1,Math.ceil(total/size));
  const p=opts.page??Math.min(pageNos[id]||1,maximum);pageNos[id]=p;
  const rows=opts.external?all:all.slice((p-1)*size,p*size);
  const signature=JSON.stringify([title,headers,rows,p,total,opts.note]);
  if(fingerprints[id]===signature)return;fingerprints[id]=signature;
 const host=$(id),oldScroll=host.querySelector('.scroll'),sx=oldScroll?.scrollLeft||0,sy=oldScroll?.scrollTop||0;
  const head=el('div',null,'articlehead');head.append(el('h2',title),el('span',total+' 条','count'));
  const scroll=el('div',null,'scroll');scroll.tabIndex=0;scroll.setAttribute('aria-label',title);
  if(rows.length){const t=el('table'),thead=t.createTHead(),tr=thead.insertRow();for(const h of headers)tr.append(el('th',h));
    const body=t.createTBody();for(const row of rows){const r=body.insertRow();for(let i=0;i<headers.length;i++){const v=row[i];const cell=r.insertCell();
      if(opts.cancel&&i===headers.length-1){cell.append(button('取消等待',()=>cancelWait(v)));continue}
      cell.textContent=v??'—';if(opts.wrap?.includes(i))cell.className='wrap';
      if(v==='多'||v==='开多'||v==='平空')cell.classList.add('up');if(v==='空'||v==='开空'||v==='平多')cell.classList.add('down');
    }}scroll.append(t)
  }else scroll.append(el('div',opts.empty||'暂无记录','empty'));
  const foot=el('div',null,'pager');const jump=n=>{if(opts.onPage)return opts.onPage(n);if(opts.external){histPage=n;return refreshHistory()}pageNos[id]=n;renderRun()};
  const back=button('‹',()=>jump(p-1)),next=button('›',()=>jump(p+1));back.title='上一页';next.title='下一页';back.disabled=p<=1;next.disabled=p>=maximum;
  foot.append(back,el('span','第 '+p+' / '+maximum+' 页'),next);const extras=[];if(opts.note){const info=el('details',null,'tableNote');info.open=Boolean(host.querySelector('details')?.open);info.append(el('summary','估算依据'),el('p',opts.note));extras.push(info)}host.replaceChildren(head,scroll,...extras,foot);scroll.scrollLeft=sx;scroll.scrollTop=sy;
}
function renderRun(){
 const s=state;if(!s)return;
 set('leaderName',s.name||'待核对带单员');set('pid','组合 ID　'+s.config.portfolio);set('runBadge',s.running?(modeNames[s.mode]||'启动中'):'未运行');
 set('pnl',showMoney?s.pnl:'****');set('wallet',showMoney?s.wallet:'****');set('duration',duration(s.duration));set('margin',s.margin);
 $('wallet').title='钱包更新 '+dated(s.walletAt)+(s.walletError?' · '+s.walletError:'');$('margin').title='官网保证金更新 '+dated(s.marginAt);
 set('strategy',(s.running?(modeNames[s.mode]||'启动中'):'运行控制')+(s.running&&s.mode.endsWith('-web')?'　·　'+(s.strategy==='EXPOSURE'?'仓位复制 '+s.factor+'×':'按点位跟随'):''));
 set('status',[s.status,s.copyReplanPending&&'复制系数已保存 · 等待官网与账户核对'].filter(Boolean).join('\n'));set('capture',[s.capture,s.captureError,s.pending?'委托待核对 '+s.pending:'',s.accountStatus,s.walletError].filter(Boolean).join('\n'));
 visible('start',!s.running);for(const id of ['pause','stop'])visible(id,s.running);
 visible('factor',s.running&&s.strategy==='EXPOSURE'&&s.mode.endsWith('-web'));set('pause',s.opens?'暂停开仓':'恢复开仓');
 visible('limited',s.running&&Boolean(s.access||s.baselineWaiting));
 set('region',s.access?'查看官网提示':'查看官网列表');
 set('webStatus',s.access?'官网访问需要处理':'建档分页等待重新读取');visible('initial',Boolean(s.initial));
 tablePanel('leaderPanel','带单员推算持仓',['合约','方向','份额','推算均价','估算强平价','估算浮盈亏','更新'],s.leader,{empty:'读取官网成交后，从已知记录推算持仓',note:s.riskNote});
 tablePanel('minePanel','我的当前持仓',['合约','方向','份额','开仓均价','保证金','强平价','杠杆','浮盈亏','更新'],s.mine,{empty:s.accountAt?'当前账户空仓':'等待账户数据'});
 $('minePanel').title='账户更新时间 '+dated(s.accountAt);$('leaderPanel').title='根据公开成交推算，强平价为估算';
 tablePanel('waitPanel','等待开仓与调仓',['合约','条件及状态','操作'],s.wait,{wrap:[1],cancel:true});
 tablePanel('detectPanel','检测到的新增操作',['成交及检测时间'],s.detections.map(x=>[x]),{wrap:[0],external:true,total:s.detectionTotal,page:s.detectionPage,size:s.detectionPageSize,onPage:n=>{requestedDetectionPage=n;return poll()}});
 tablePanel('logPanel','自动运行记录',['操作详情'],s.log.map(x=>[x]),{wrap:[0]});
}
const historyHeaders={trades:['成交时间','合约','方向','份额','成交价格','成交额','已实现盈亏','检测时间'],fills:['成交时间','交易环境','成交详情'],transfers:['划转时间','资产','金额','转出','转入']};
async function refreshHistory(){if(view!=='history'||!state)return;const serial=++historySerial,kind=hist,pf=state.config.portfolio;
 const result=await rpc('history',{type:kind,page:histPage});if(serial!==historySerial||view!=='history'||pf!==state.config.portfolio)return;
 histPage=result.page;tablePanel('historyPanel',{trades:'带单员成交',fills:'我的成交',transfers:'带单保证金划转'}[kind],historyHeaders[kind],result.rows,{external:true,total:result.total,page:result.page,size:result.size,wrap:kind==='fills'?[2]:[]});
 set('histName',state.name||'带单员档案');set('histId','组合 ID　'+pf);set('balanceStatus',kind==='transfers'?state.balanceStatus:state.historyDetail);historyAt=Date.now()
}
function switchView(name){view=name;for(const n of ['run','history','settings'])visible(n,n===name);
 document.querySelectorAll('[data-tab]').forEach(b=>{b.classList.toggle('current',b.dataset.tab===name);b.setAttribute('aria-label',b.innerText.trim()||{run:'运行总览',history:'成交记录',settings:'跟单设置'}[b.dataset.tab]);b.title={run:'运行总览',history:'成交记录',settings:'跟单设置'}[b.dataset.tab]});
 const label={run:'运行总览',history:'成交记录',settings:'跟单设置'}[name];set('title',label);set('crumb',label);
 if(name==='history')refreshHistory().catch(e=>toast(e.message));if(name==='settings')renderSettings();$('workspace').scrollTo(0,0)}
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>switchView(b.dataset.tab));
document.querySelectorAll('[data-hist]').forEach(b=>b.onclick=action(async()=>{hist=b.dataset.hist;histPage=1;document.querySelectorAll('[data-hist]').forEach(x=>x.classList.toggle('selected',x===b));await refreshHistory()}));
async function poll(){if(busyPoll)return;busyPoll=true;try{const next=await rpc('state',{detectionPage:requestedDetectionPage});if(state&&state.config.portfolio!==next.config.portfolio)requestedDetectionPage=1;state=next;renderRun();if(view==='settings')renderSettings();if(view==='history'&&Date.now()-historyAt>2500)await refreshHistory();
 if(first){first=false;if(state.showIntro){visible('splash',true);setTimeout(async()=>{visible('splash',false);try{await rpc('introSeen')}catch(e){toast(e.message)}if(!state.accepted)welcome()},3000)}else if(!state.accepted)welcome()}}catch(e){toast(e.message)}finally{busyPoll=false}}
function renderSettings(){if(!state)return;const key=JSON.stringify(state.config);
 if(!dirty&&key!==draftKey){draft=state.config.contractRules.map(x=>({...x}));draftKey=key;$('portfolio').value=state.config.portfolio;renderRules()}
 const prior=JSON.stringify(state.portfolios);if($('previous').dataset.fingerprint!==prior){$('previous').dataset.fingerprint=prior;$('previous').replaceChildren(...state.portfolios.map(([id,name])=>{const o=el('option');o.value=id;o.label=name||id;return o}));}
 set('keyStatus',state.keys.includes($('realm').value)?'当前环境已保存凭证':'当前环境尚未设置凭证');$('keys').disabled=state.running;$('query').disabled=state.running;
}
$('portfolio').oninput=()=>dirty=true;$('realm').onchange=renderSettings;
function renderRules(){const box=$('rules');box.replaceChildren();for(const [i,r] of (draft||[]).entries()){
 const wrap=el('div',null,'rule'),info=el('div');info.append(el('b',r.symbol),el('small',(r.ratio?'钱包保证金 '+r.amount+'%':'定额 '+r.amount+' USDT')+'　·　'+r.leverage+'×　·　不利价差 '+r.slippage+'%'));
 const controls=el('div',null,'buttons');controls.append(button('编辑',()=>ruleModal(i)),button('移除',()=>{draft.splice(i,1);dirty=true;renderRules()}));wrap.append(info,controls);box.append(wrap)}}
function ruleModal(index){const r=draft[index]||{symbol:'',ratio:false,amount:'20',leverage:5,slippage:'0.3'};
 const [sym,s]=formField('合约代码','text',r.symbol),[mod,m]=formField('保证金模式','select',r.ratio?'ratio':'fixed',[['fixed','定额 · USDT'],['ratio','定比 · 钱包余额百分比']]),[amt,a]=formField('保证金数额 / 比例','text',r.amount),[lev,l]=formField('杠杆倍数','text',r.leverage),[slip,p]=formField('最大不利价差（%）','text',r.slippage);
 const grid=el('div',null,'fieldgrid');grid.append(sym,mod,amt,lev,slip);
 const list=el('datalist');list.id='symbols';s.setAttribute('list','symbols');s.disabled=index>=0;
 const load=button('从币安实盘加载合约列表',async()=>{const res=await rpc('symbols');list.replaceChildren(...res.items.map(v=>{const o=el('option');o.value=v;return o}));s.focus();toast('可输入代码筛选 '+res.items.length+' 个合约')});
 modal(index<0?'添加合约':'编辑 '+r.symbol,[grid,list,load],[button('取消',close),confirmButton('应用',()=>{const symbol=s.value.trim().toUpperCase();
 if(!/^[A-Z0-9\p{Script=Han}]{1,30}USDT$/u.test(symbol)||!Number.isInteger(+l.value)||+l.value<1||+a.value<=0||+p.value<0||![a,p].every(x=>x.value.trim()&&Number.isFinite(+x.value)))throw Error('请核对合约、保证金、杠杆和最大价差');
 if(draft.some((v,j)=>v.symbol===symbol&&j!==index))throw Error('该合约已经添加');const value={symbol,ratio:m.value==='ratio',amount:a.value.trim(),leverage:+l.value,slippage:p.value.trim()};if(index<0)draft.push(value);else draft[index]=value;dirty=true;renderRules();close()})])}
click('addRule',()=>ruleModal(-1));click('saveRules',async()=>{if(!draft?.length)throw Error('请至少添加一个合约');const portfolio=$('portfolio').value.trim();if(!/^\d{8,25}$/.test(portfolio))throw Error('请输入 8 至 25 位数字组合 ID');const b=$('saveRules');b.disabled=true;set('saveRules','正在核对…');
 try{await rpc('rules',{portfolio,contractRules:draft});dirty=false;draftKey='';await poll();toast('跟单规则已保存')}finally{b.disabled=false;set('saveRules','保存跟单规则')}});
click('mask',()=>{showMoney=!showMoney;renderRun()});
click('start',()=>{if(!state?.accepted)return welcome();
 const [mode,m]=formField('运行方式','select','watch',Object.entries(modeNames)),[str,t]=formField('跟单策略','select','POINTS',[['POINTS','按点位跟随'],['EXPOSURE','复制带单员仓位比例']]);
 const [factor,f]=formField('复制系数','text',state.factor),[slip,p]=formField('调仓最大不利价差（%）','text',state.slip);
 const notice=el('p','','note');function adapt(){const linked=m.value.endsWith('-web');str.hidden=!linked;slip.hidden=!linked;factor.hidden=!(linked&&t.value==='EXPOSURE');
 notice.textContent=m.value==='watch'?'仅采集币安官网成交和保证金划转。':m.value==='testnet-drill'?'在币安测试网提交演练委托。':m.value==='mainnet-web'?'将使用实盘账户。官网信号可能延迟，行情变化可能造成滑点。':'官网信号驱动币安测试网委托。';if(linked)notice.textContent+='首次补已有仓位使用调仓点差。'}m.onchange=t.onchange=adapt;adapt();
 modal('启动跟单',[mode,str,factor,slip,notice],[button('取消',close),confirmButton('继续',async()=>{await rpc('start',{mode:m.value,strategy:m.value.endsWith('-web')?t.value:'POINTS','copy-factor':f.value,'copy-slippage':p.value});close();await poll()})])});
click('pause',async()=>{await rpc(state.opens?'pause':'resume');await poll()});
click('stop',()=>modal('停止本次运行',[el('p','已持有的仓位会继续保留。重新启动时会核对已提交的委托。')],[button('返回',close),confirmButton('停止运行',async()=>{await rpc('stop');close();await poll()})]));
click('factor',()=>{const [factor,f]=formField('复制系数','text',state.factor),[slip,s]=formField('调仓最大不利价差（%）','text',state.slip);
 modal('调整复制系数',[factor,slip,el('p','达到新目标需要减仓时，直接提交市价单。需要加仓时，按这里的调仓价差等待，可能部分成交或长期无法成交。市价单可能产生滑点。','note')],[button('取消',close),confirmButton('确认调整',async()=>{await rpc('copy-factor',{portfolio:state.config.portfolio,mode:state.mode,'copy-factor':f.value,'copy-slippage':s.value});close();await poll()})])});
click('chooseInitial',()=>{const v=state.initial;if(!v)return;const boxes=[];const rows=v.options.map(([id,s,a,q,p])=>{const l=el('label',null,'inline'),i=el('input');i.type='checkbox';i.disabled=!v.canAdd;i.value=id;boxes.push(i);l.append(i,el('span',s+' · '+a+'　'+q+' @ '+p));return l});
 modal('选择补上的已有仓位',[el('p','只勾选要补跟的合约与方向。全部留空即可跳过。','note'),...(v.awaitingReview?[el('p',v.awaitingReview+' 个方向等待同秒成交核对','note')]:[]),...rows],[button('稍后',close),confirmButton('确认选择',async()=>{await rpc('initial-choice',{portfolio:v.portfolio,head:v.head,backfilled:v.backfilled,positions:boxes.filter(b=>b.checked).map(b=>b.value)});close();await poll()})])});
function cancelWait(id){modal('取消这次等待',[el('p','该信号的未成交部分将停止等待。已提交的委托仍需核对。')],[button('返回',close),confirmButton('取消等待',async()=>{await rpc('cancel',{mode:state.mode,id});close();await poll()})])}
click('region',()=>rpc('region'));click('retry',()=>rpc('retry-web'));click('sound',()=>rpc('sound'));click('exit',()=>rpc('exit'));
click('export',async()=>{const r=await rpc('export');if(!r.cancelled)toast('成交档案已导出')});
click('keys',()=>{const [kl,k]=formField('API Key','password',''),[sl,s]=formField('API Secret','password','');const realm=$('realm').value;
 modal('设置 '+$('realm').selectedOptions[0].textContent+' 凭证',[kl,sl,el('p','交易密钥需开通合约交易权限。凭证仅用于连接币安。','note')],[button('取消',close),confirmButton('加密保存',async()=>{await rpc('credentials',{realm,key:k.value.trim(),secret:s.value.trim()});k.value=s.value='';close();await poll();toast('凭证已加密保存')})]);$('dialog').addEventListener('close',()=>{k.value=s.value=''}, {once:true})});
click('query',async()=>{const b=$('query');b.disabled=true;try{await rpc('account',{realm:$('realm').value});await poll();switchView('run');toast('账户数据已更新')}finally{b.disabled=false}});
function copyRow(value){const div=el('div',null,'copyrow');div.append(el('span',value),button('复制',async()=>{await rpc('clip',{value});toast('已复制')}));return div}
click('donate',()=>modal('支持更新',[el('b','币安转账 ID'),copyRow('1218112316'),el('b','BNB Chain · USDT'),copyRow('0x46CE8C06265b4d7667AA2cb693B71193f9F0C6ae'),el('p','如果这款工具有帮到你，欢迎捐赠，多少都不嫌弃，你的支持是我更新的动力。','note')],[button('关闭',close)]));
click('author',()=>modal('作者信息',[el('h3','色胚头子 crypto trader'),el('b','X'),copyRow('@wuyanzuWa'),el('b','邮箱'),copyRow('wuy15525@gmail.com'),el('p','请勿用于商业以及违法用途。')],[button('关闭',close)]));
function legal(fromWelcome){modal('免责声明',legalText.map(x=>el('p',x)),[button(fromWelcome?'返回使用提示':'关闭',fromWelcome?welcome:close)],fromWelcome)}
function welcome(){modal('使用提示',[el('p','请勿用于商业以及违法用途。交易所合约具有杠杆风险，请在使用前了解当地限制与本工具的能力边界。')],[button('查免责声明',()=>legal(true)),confirmButton('我知道了',async()=>{await rpc('accept');close();await poll()})],true)}
click('legal',()=>legal(false));
setInterval(()=>set('clock',new Date().toLocaleTimeString('zh-CN',{hour12:false})),1000);setInterval(poll,1000);switchView('run');poll();
