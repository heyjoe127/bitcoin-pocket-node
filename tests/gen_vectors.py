#!/usr/bin/env python3
"""Generate UTXOracle test vectors from a live Bitcoin node."""
import json, http.client, base64, sys
from math import log10, exp, fabs

def rpc(method, params=[], user="pocketnode", pw="b7fxw3G3CxobeacFm3TjvfQmZpIjVqWo", port=8332):
    auth = base64.b64encode(f"{user}:{pw}".encode()).decode()
    body = json.dumps({"jsonrpc":"1.0","method":method,"params":params})
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=120)
    conn.request("POST", "/", body, {"Authorization":f"Basic {auth}","Content-Type":"text/plain"})
    r = json.loads(conn.getresponse().read().decode()); conn.close()
    if r.get("error"): raise Exception(r["error"])
    return r["result"]

def filter_block(block_json, existing_txids):
    txs = block_json["tx"]; block_txids = set(); filtered = []
    for tx in txs:
        txid = tx["txid"]; vin = tx["vin"]; vout = tx["vout"]
        ic = len(vin); oc = len(vout)
        is_cb = ic > 0 and "coinbase" in vin[0]
        input_txids = [inp["txid"] for inp in vin if "txid" in inp]
        has_opr = any(o.get("scriptPubKey",{}).get("type")=="nulldata" for o in vout)
        vals = [o["value"] for o in vout if o["value"]>1e-5 and o["value"]<1e5]
        wit_exc = False
        for inp in vin:
            tw = 0
            for w in inp.get("txinwitness",[]):
                il = len(w)//2; tw += il
                if il>500 or tw>500: wit_exc=True; break
            if wit_exc: break
        block_txids.add(txid)
        same_day = any(t in existing_txids or t in block_txids for t in input_txids)
        if ic<=5 and oc==2 and not is_cb and not has_opr and not wit_exc and not same_day:
            filtered.extend(vals)
    return filtered, block_txids

def calc_price(raw_outputs, block_heights, block_times):
    """Steps 5-11, identical to UTXOracle.py v9.1"""
    bins = [0.0]
    for ex in range(-6,6):
        for b in range(200): bins.append(10**(ex+b/200.0))
    nb = len(bins); bc = [0.0]*nb
    for a in raw_outputs:
        al = log10(a); p = (al-(-6))/12.0; be = int(p*nb)
        while bins[be]<=a: be+=1
        bc[be-1]+=1.0

    s5sum = sum(bc)
    for n in range(201): bc[n]=0.0
    for n in range(1601,nb): bc[n]=0.0
    for r in [201,401,461,496,540,601,661,696,740,801,861,896,940,1001,1061,1096,1140,1201]:
        bc[r]=0.5*(bc[r+1]+bc[r-1])
    cs = sum(bc[201:1601])
    for n in range(201,1601):
        bc[n]/=cs
        if bc[n]>0.008: bc[n]=0.008

    ne=803; mn=411; sd=201
    ss=[0.00150*exp(-((x-mn)**2)/(2.0*sd**2))+0.0000005*x for x in range(ne)]
    sp=[0.0]*ne
    for i,v in [(40,0.001300198324984352),(141,0.001676746949820743),(201,0.003468805546942046),
        (202,0.001991977522512513),(236,0.001905066647961839),(261,0.003341772718156079),
        (262,0.002588902624584287),(296,0.002577893841190244),(297,0.002733728814200412),
        (340,0.003076117748975647),(341,0.005613067550103145),(342,0.003088253178535568),
        (400,0.002918457489366139),(401,0.006174500465286022),(402,0.004417068070043504),
        (403,0.002628663628020371),(436,0.002858828161543839),(461,0.004097463611984264),
        (462,0.003345917406120509),(496,0.002521467726855856),(497,0.002784125730361008),
        (541,0.003792850444811335),(601,0.003688240815848247),(602,0.002392400117402263),
        (636,0.001280993059008106),(661,0.001654665137536031),(662,0.001395501347054946),
        (741,0.001154279140906312),(801,0.000832244504868709)]:
        sp[i]=v

    cp=601; hs=(len(sp)+1)//2; lp=cp-hs; rp=cp+hs
    bsl=0; bss=0.0; ts=0.0
    for sl in range(-141,201):
        sss=0.0; sco=0.0
        for n in range(ne): cv=bc[lp+sl+n]; sss+=cv*ss[n]; sco+=cv*sp[n]
        if sl<150: sco+=sss*0.65
        if sco>bss: bss=sco; bsl=sl
        ts+=sco
    u1=bins[cp+bsl]; bu1=100.0/u1

    nus=0.0
    for n in range(ne): nus+=bc[lp+bsl+1+n]*sp[n]
    nds=0.0
    for n in range(ne): nds+=bc[lp+bsl-1+n]*sp[n]
    bn2=-1 if nds>nus else 1; ns=max(nus,nds)
    u2=bins[cp+bsl+bn2]; bu2=100.0/u2
    avs=ts/(201-(-141)); a1=bss-avs; a2=abs(ns-avs)
    w1=a1/(a1+a2); w2=a2/(a1+a2)
    rough=int(w1*bu1+w2*bu2)

    usds=[5,10,15,20,25,30,40,50,100,150,200,300,500,1000]; prw=0.25
    mr=[]
    ii=0.00005
    while ii<0.0001: mr.append(ii); ii+=0.00001
    ii=0.0001
    while ii<0.001: mr.append(ii); ii+=0.00001
    ii=0.001
    while ii<0.01: mr.append(ii); ii+=0.0001
    ii=0.01
    while ii<0.1: mr.append(ii); ii+=0.001
    ii=0.1
    while ii<1.0: mr.append(ii); ii+=0.01
    pmr=0.0001

    op=[]
    for i,n in enumerate(raw_outputs):
        for u in usds:
            av=u/rough; up=av+prw*av; dn=av-prw*av
            if n>dn and n<up:
                ok=True
                for r in mr:
                    if n>r-pmr*r and n<r+pmr*r: ok=False; break
                if ok: op.append(u/n)

    def fco(prices,pmin,pmax):
        f=sorted([p for p in prices if p>pmin and p<pmax]); nn=len(f)
        if nn==0: return float(rough),0.0
        ps=[0.0]*nn; t=0.0
        for i in range(nn): t+=f[i]; ps[i]=t
        md=float('inf'); bi=0
        for i in range(nn):
            ls=ps[i-1] if i>0 else 0.0; rs=t-ps[i]
            d=(f[i]*i-ls)+(rs-f[i]*(nn-i-1))
            if d<md: md=d; bi=i
        bo=f[bi]; dv=sorted([abs(p-bo) for p in f]); m=len(dv)
        mad=(dv[m//2-1]+dv[m//2])/2.0 if m%2==0 else dv[m//2]
        return bo,mad

    prt=0.05; pu=rough+prt*rough; pd=rough-prt*rough
    cp2,_=fco(op,pd,pu); avset={cp2}
    for _ in range(100):
        pu=cp2+prt*cp2; pd=cp2-prt*cp2
        np2,nd=fco(op,pd,pu)
        if np2 in avset: cp2=np2; break
        avset.add(np2); cp2=np2

    return int(cp2), rough, len(op), s5sum, cs

# Main
print("Fetching blocks...")
bc = rpc("getblockcount")
nums,hashes,times=[],[],[]
for bn in range(bc-144, bc):
    h=rpc("getblockhash",[bn]); hdr=rpc("getblockheader",[h,True])
    nums.append(bn); hashes.append(h); times.append(hdr["time"])
    if len(nums)%20==0: print(f"  Headers: {len(nums)}/144")

print(f"Processing {len(nums)} blocks ({nums[0]}..{nums[-1]})")
outs,heights,btimes=[],[],[]
etx=set()
for i,(bn,bh,bt) in enumerate(zip(nums,hashes,times)):
    if i%20==0: print(f"  Block {i+1}/144 (h={bn})")
    blk=rpc("getblock",[bh,2])
    f,btxids=filter_block(blk,etx); etx.update(btxids)
    for v in f: outs.append(v); heights.append(bn); btimes.append(bt)

print(f"Filtered outputs: {len(outs)}")
fp,rp,oc,s5,s7=calc_price(outs,heights,btimes)
print(f"Rough: ${rp}  Final: ${fp}  Outputs: {oc}")

vectors={"block_range":[nums[0],nums[-1]],"filtered_outputs":outs,
    "block_heights":heights,"block_times":btimes,
    "rough_price":rp,"expected_price":fp,"output_count":oc}
with open("oracle_test_vectors.json","w") as f: json.dump(vectors,f)
print("Saved oracle_test_vectors.json")
