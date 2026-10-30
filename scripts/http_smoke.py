"""Real HTTP smoke/load for the Java jar, with native PostgreSQL; no Python backend."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import json,math,os,subprocess,time,uuid
import urllib.request,urllib.error

ROOT=Path(__file__).resolve().parents[1]
os.chdir(ROOT)
java=Path(os.environ['JAVA_HOME'])/'bin/java.exe'
tenant='http-'+uuid.uuid4().hex[:12]
token=subprocess.run([str(java),'-jar','target/ev-fleet-1.0.0.jar','--issue-token',tenant,'admin','demo-admin'],capture_output=True,text=True,check=True).stdout.strip()
def request(path,body=None,method='GET',auth=True):
    headers={'Content-Type':'application/json','Idempotency-Key':'smoke'}
    if auth:headers['Authorization']='Bearer '+token
    value=json.dumps(body).encode() if body is not None else None
    req=urllib.request.Request('http://127.0.0.1:8106'+path,data=value,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as r:
            text=r.read().decode();return r.status,json.loads(text) if 'json' in r.headers.get('Content-Type','') else text
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read())

log=open(ROOT/'evidence/http-server.log','w',encoding='utf8')
proc=subprocess.Popen([str(java),'-Xmx1g','-Djava.io.tmpdir='+str(ROOT/'.cache/tmp'),'-jar','target/ev-fleet-1.0.0.jar'],stdout=log,stderr=log)
try:
    for _ in range(300):
        try:
            if request('/health',auth=False)[0]==200:break
        except OSError:pass
        if proc.poll() is not None:raise RuntimeError('Java server exited: inspect evidence/http-server.log')
        time.sleep(.1)
    else:raise RuntimeError('Server did not start')
    def poll(jid):
        for _ in range(500):
            _,job=request('/api/v1/jobs/'+jid)
            if job['status']=='SUCCEEDED':return job['result']
            if job['status'] in ('FAILED','SUPERSEDED'):raise AssertionError(job)
            time.sleep(.02)
        raise RuntimeError('Job timeout')
    assert request('/api/v1/datasets',json.loads((ROOT/'examples/demo-dataset.json').read_text()),'POST')[0]==201
    _,job=request('/api/v1/plans',method='POST');initial=poll(job['jobId'])
    assert request('/api/v1/plans/'+initial+'/validate',method='POST')[1]['valid']
    started=time.perf_counter();_,accepted=request('/api/v1/disruptions',{'eventId':'soc','sequence':1,'at':0,'kind':'soc','assetId':'ev-b','energyWh':18000,'location':'office'},'POST');repaired=poll(accepted['jobId']);repair=time.perf_counter()-started
    assert request('/api/v1/plans/'+repaired+'/validate',method='POST')[1]['valid']
    _,accepted=request('/api/v1/disruptions',{'eventId':'failure','sequence':1,'at':0,'kind':'charger_failure','assetId':'plug-1'},'POST');failed=poll(accepted['jobId'])
    status,current=request('/api/v1/plans/current');assert status==200 and current['dispatchable'] and current['plan']['validation']['valid']
    assert request('/demo',auth=False)[0]==200
    assert request('/actuator/prometheus')[0]==200
    def read(_):
        start=time.perf_counter();assert request('/api/v1/plans/current')[0]==200;return time.perf_counter()-start
    started=time.perf_counter()
    with ThreadPoolExecutor(4) as pool:samples=list(pool.map(read,range(80)))
    total=time.perf_counter()-started;ordered=sorted(samples)
    outage='UNVERIFIED: externally managed database'
    pg=ROOT/'.runtime/pgsql/bin/pg_ctl.exe'
    if pg.exists() and '127.0.0.1:5546' in os.environ['DATABASE_URL']:
        subprocess.run([str(pg),'-D',str(ROOT/'.runtime/pgdata'),'-m','fast','-w','stop'],capture_output=True,check=True)
        assert request('/health',auth=False)==(503,{'error':{'code':'DATABASE_UNAVAILABLE'}})
        outage='PASS: actual PostgreSQL stop gives redacted 503'
    result={'language':'Java / Spring Boot','transport':'real loopback HTTP 8106 + PostgreSQL','fixture':'2 vehicles / 1 charger / 3 trips','initialValid':True,'socRepairValid':True,'chargerFailureValid':True,'socRepairRoundtripPollingS':repair,'finalBackups':current['totalBackupCount'],'demoHtmlServed':True,'readLoad':{'n':80,'concurrency':4,'p50S':(ordered[39]+ordered[40])/2,'p99S':ordered[79],'maxS':ordered[-1],'throughputRps':80/total,'errors':0},'databaseOutage':outage,'browserVisualCheck':'Pending separate browser check'}
    (ROOT/'evidence/http-smoke.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
finally:
    proc.terminate();proc.wait(timeout=20);log.close()
