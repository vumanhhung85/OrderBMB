/*************************************************************
 * BOOM MUSIC BOX — HỆ THỐNG GỌI PHỤC VỤ TẠI PHÒNG
 * Backend Google Apps Script (gắn vào 1 Google Sheet riêng,
 * KHÔNG dùng chung Sheet với app BMB hiện có).
 *
 * Phase 1: chỉ gọi phục vụ, không giá, không tính tiền.
 * Đường nóng sẽ chuyển sang Cloudflare Worker ở phase 2.
 *
 * !!! MỖI LẦN SỬA FILE NÀY PHẢI:
 *     Deploy → Manage deployments → ✏️ → Version: New → Deploy
 *     Chỉ bấm Lưu (Ctrl+S) thì URL đang chạy KHÔNG đổi.
 *************************************************************/

var CFG = {
  /* --- bí mật: đổi trước khi chạy thật, đổi xong thì mọi mật khẩu cũ hỏng --- */
  SALT   : 'bmb.order.v1',                  /* phải giống CFG.SALT trong quay.html */
  SECRET : 'DOI-CHUOI-BI-MAT-NAY-DI-1234',  /* chỉ nằm ở server */

  /* --- đường dẫn trang khách, dùng để sinh nội dung mã QR --- */
  BASE_URL : 'https://TEN-GITHUB.github.io/bmb-order/index.html',

  /* --- Telegram: để trống thì hệ thống bỏ qua, không lỗi --- */
  TG_TOKEN : '',

  /* --- ngưỡng leo thang (phút) --- */
  SLA1 : 3,    /* chưa ai tiếp nhận → báo group chi nhánh */
  SLA2 : 10,   /* chưa hoàn tất    → báo group chi nhánh + quản lý */

  PHIEN_GIO  : 10,   /* phiên đăng nhập quầy hết hạn sau bao nhiêu giờ */
  IP_CONG_NHAN : 2,  /* thấy đủ số lần này trong 24h thì công nhận IP */
  IP_HET_HAN_H : 48, /* không thấy lại sau bấy nhiêu giờ thì loại */
  PHONG_CANH_BAO_H : 6 /* phòng mở quá lâu thì bôi vàng */
};

var SHEETS = {
  CN:'CHI_NHANH', PHONG:'PHONG', DON:'DON', TK:'TAI_KHOAN',
  IP:'IP_HOC', CHAN:'CHAN_TB', LOG:'NHAT_KY'
};

/* ============ TIỆN ÍCH ============ */
function ss(){ return SpreadsheetApp.getActiveSpreadsheet(); }
function sh(n){
  var s = ss().getSheetByName(n);
  if(!s) throw new Error('Thiếu sheet "'+n+'". Chạy hàm khoiTao() một lần trước đã.');
  return s;
}
function tz(){ return ss().getSpreadsheetTimeZone(); }
function now(){ return new Date(); }
function iso(d){ return d ? Utilities.formatDate(d, tz(), "yyyy-MM-dd'T'HH:mm:ssXXX") : ''; }
function hhmm(d){ return d ? Utilities.formatDate(d, tz(), 'HH:mm') : ''; }
function ngay(d){ return Utilities.formatDate(d, tz(), 'yyyy-MM-dd'); }
function rid(n){
  var c = 'abcdefghjkmnpqrstuvwxyz23456789', s = '';
  for(var i=0;i<n;i++) s += c.charAt(Math.floor(Math.random()*c.length));
  return s;
}
function sha256Hex(s){
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, s, Utilities.Charset.UTF_8)
    .map(function(b){ return ('0'+(b & 0xFF).toString(16)).slice(-2); }).join('');
}
function hmacHex(s){
  return Utilities.computeHmacSha256Signature(s, CFG.SECRET)
    .map(function(b){ return ('0'+(b & 0xFF).toString(16)).slice(-2); }).join('');
}
function out(o){
  return ContentService.createTextOutput(JSON.stringify(o))
    .setMimeType(ContentService.MimeType.JSON);
}
function log(loai, nd){
  try{ sh(SHEETS.LOG).appendRow([now(), loai, String(nd).slice(0,900)]); }catch(e){}
}

/* ============ ROUTER ============ */
function doPost(e){
  var d = {};
  try{ d = JSON.parse(e.postData.contents); }catch(err){ return out({ok:false,msg:'BODY_LOI'}); }
  try{
    switch(d.action){
      /* --- công khai (từ tablet / điện thoại khách) --- */
      case 'phong.resolve'  : return out(apiResolve(d));
      case 'don.tao'        : return out(apiTaoDon(d));
      case 'don.trangThai'  : return out(apiTrangThai(d));
      /* --- quầy (cần token phiên) --- */
      case 'auth.login'     : return out(apiLogin(d));
      case 'quay.feed'      : return out(apiFeed(d));
      case 'quay.dsPhong'   : return out(apiDsPhong(d));
      case 'quay.phong'     : return out(apiSetPhong(d));
      case 'quay.don'       : return out(apiXuLyDon(d));
      case 'quay.pos'       : return out(apiDanhDauPos(d));
      case 'quay.chan'      : return out(apiChanTb(d));
      default: return out({ok:false,msg:'ACTION_LA'});
    }
  }catch(err){
    log('LOI', d.action + ' :: ' + err);
    return out({ok:false, msg:'LOI_SERVER'});
  }
}
function doGet(){
  return ContentService.createTextOutput('BMB Order API — OK').setMimeType(ContentService.MimeType.TEXT);
}

/* ============ PHIÊN ĐĂNG NHẬP ============ */
function taoPhien(user, branchId, vaiTro){
  var het = Date.now() + CFG.PHIEN_GIO*3600*1000;
  var p = [user, branchId, vaiTro, het].join('|');
  return Utilities.base64EncodeWebSafe(p) + '.' + hmacHex(p).slice(0,32);
}
function docPhien(tok){
  if(!tok || tok.indexOf('.')<0) return null;
  var a = tok.split('.');
  var p;
  try{ p = Utilities.newBlob(Utilities.base64DecodeWebSafe(a[0])).getDataAsString(); }catch(e){ return null; }
  if(hmacHex(p).slice(0,32) !== a[1]) return null;
  var x = p.split('|');
  if(Number(x[3]) < Date.now()) return null;
  return { user:x[0], branchId:x[1], vaiTro:x[2] };
}
function canPhien(d){
  var s = docPhien(d.tok);
  if(!s) throw { phien:true };
  return s;
}

/* ============ ĐỌC BẢNG ============ */
function doc(name){
  var s = sh(name), v = s.getDataRange().getValues();
  if(v.length < 2) return [];
  var head = v[0], rows = [];
  for(var i=1;i<v.length;i++){
    var o = {_row: i+1};
    for(var j=0;j<head.length;j++) o[head[j]] = v[i][j];
    rows.push(o);
  }
  return rows;
}
function timPhongTheoToken(token){
  return doc(SHEETS.PHONG).filter(function(r){ return String(r.token)===String(token); })[0] || null;
}
function chiNhanh(id){
  return doc(SHEETS.CN).filter(function(r){ return String(r.branchId)===String(id); })[0] || null;
}

/* ============ API: GIẢI MÃ TOKEN PHÒNG ============ */
function apiResolve(d){
  var p = null;
  if(d.token) p = timPhongTheoToken(d.token);
  else if(d.ma4){
    /* mã 4 số chỉ dùng cho nhân viên gán tablet — không dùng cho khách */
    var ds = doc(SHEETS.PHONG).filter(function(r){ return String(r.ma4)===String(d.ma4); });
    p = ds.length === 1 ? ds[0] : null;   /* trùng mã giữa 2 chi nhánh thì từ chối cho chắc */
  }
  if(!p) return {ok:false, msg:'KHONG_TIM_THAY'};
  var cn = chiNhanh(p.branchId);
  return {
    ok:true, token:p.token, roomId:p.roomId, roomName:p.roomName,
    branchId:p.branchId, branchName: cn ? cn.ten : String(p.branchId),
    open: p.open === true || p.open === 'TRUE'
  };
}

/* ============ API: TẠO ĐƠN ============ */
function apiTaoDon(d){
  var p = timPhongTheoToken(d.token);
  if(!p) return {ok:false, msg:'KHONG_TIM_THAY'};
  var items = d.items || [];
  if(!items.length) return {ok:false, msg:'RONG'};

  var lock = LockService.getScriptLock();
  try{ lock.waitLock(8000); }catch(e){ return {ok:false, msg:'BAN'}; }

  try{
    var t = now(), dev = String(d.dev||''), ip = String(d.ip||'');

    /* --- thiết bị đang bị chặn --- */
    var chan = doc(SHEETS.CHAN).filter(function(r){
      return String(r.deviceId)===dev && r.denLuc instanceof Date && r.denLuc > t;
    });

    /* --- chống bấm dồn: 1 đơn / 60 giây / phòng --- */
    var donHomNay = doc(SHEETS.DON).filter(function(r){ return String(r.roomId)===String(p.roomId); });
    var gan = donHomNay.filter(function(r){
      return r.thoiGian instanceof Date && (t - r.thoiGian) < 60000;
    });

    /* --- đánh giá nghi ngờ (phase 1: chỉ ghi nhận, không chặn) --- */
    var nghi = false, lyDo = '';
    var phongMo = (p.open === true || p.open === 'TRUE');
    if(!phongMo){ nghi = true; lyDo = 'phòng đang đóng'; }
    if(chan.length){ nghi = true; lyDo = 'thiết bị đã bị chặn'; }
    if(gan.length >= 3){ nghi = true; lyDo = 'gửi quá nhiều lần'; }
    if(!nghi && d.source === 'QR' && ip){
      if(!ipHopLe(p.branchId, ip)){ nghi = true; lyDo = 'không dùng wifi của quán'; }
    }

    var id = 'O' + Date.now().toString(36) + rid(3);
    sh(SHEETS.DON).appendRow([
      id, t, p.branchId, p.roomId, p.roomName,
      String(d.source||'QR'), dev, ip,
      JSON.stringify(items), String(d.note||''),
      'MOI', nghi ? 'TRUE' : '', lyDo,
      '', '', '', '', '', ''
    ]);

    /* tablet đã gán phòng là cảm biến IP đáng tin của chi nhánh */
    if(d.source === 'TABLET' && ip) hocIp(p.branchId, ip);

    /* sự cố kỹ thuật → báo ngay, không chờ leo thang */
    var coSuCo = items.some(function(i){
      return i.type === 'SV' && ['MIC','LANH','NET'].indexOf(i.code) >= 0;
    });
    if(coSuCo && !nghi){
      tele(p.branchId, '🔧 <b>' + p.roomName + '</b> báo sự cố: '
        + items.filter(function(i){return i.type==='SV';}).map(function(i){return i.label;}).join(', ')
        + '\n<i>' + hhmm(t) + '</i>', true);
    }
    return {ok:true, orderId:id};
  } finally {
    lock.releaseLock();
  }
}

/* ============ API: TRẠNG THÁI ĐƠN ============ */
function apiTrangThai(d){
  var r = doc(SHEETS.DON).filter(function(x){ return String(x.orderId)===String(d.orderId); })[0];
  if(!r) return {ok:false};
  return {ok:true, status: String(r.trangThai||'MOI')};
}

/* ============ API: ĐĂNG NHẬP ============ */
function apiLogin(d){
  var u = String(d.user||'').trim().toLowerCase();
  var rows = doc(SHEETS.TK).filter(function(r){ return String(r.taikhoan).toLowerCase()===u; });
  if(!rows.length) return {ok:false, msg:'Sai tài khoản hoặc mật khẩu.'};
  var tk = rows[0];

  if(tk.khoaDen instanceof Date && tk.khoaDen > now())
    return {ok:false, msg:'Tài khoản tạm khoá, thử lại sau vài phút.'};

  var dung = sha256Hex(CFG.SECRET + '|' + String(d.pass||'')) === String(tk.matKhauHash);
  var s = sh(SHEETS.TK);
  if(!dung){
    var sai = Number(tk.saiLienTiep||0) + 1;
    s.getRange(tk._row, 5).setValue(sai);
    if(sai >= 5){
      s.getRange(tk._row, 6).setValue(new Date(Date.now() + 5*60*1000));
      s.getRange(tk._row, 5).setValue(0);
      log('BAO_MAT', 'Khoá tài khoản ' + u + ' do sai 5 lần');
    }
    return {ok:false, msg:'Sai tài khoản hoặc mật khẩu.'};
  }
  s.getRange(tk._row, 5).setValue(0);
  var cn = chiNhanh(tk.branchId);
  return {
    ok:true, tok: taoPhien(u, tk.branchId, tk.vaiTro||'QUAY'),
    branchId: tk.branchId, branchName: cn ? cn.ten : String(tk.branchId)
  };
}

/* ============ API: DỮ LIỆU QUẦY ============ */
function apiFeed(d){
  var s;
  try{ s = canPhien(d); }catch(e){ return {ok:false, msg:'HET_PHIEN'}; }
  var hnay = ngay(now()), t = now();

  var rooms = doc(SHEETS.PHONG)
    .filter(function(r){ return String(r.branchId)===String(s.branchId); })
    .map(function(r){
      var mo = (r.open===true || r.open==='TRUE');
      var h = (mo && r.moLuc instanceof Date) ? Math.floor((t - r.moLuc)/3600000) : 0;
      return {roomId:String(r.roomId), roomName:String(r.roomName), open:mo, hours:h};
    });

  var orders = doc(SHEETS.DON)
    .filter(function(r){
      return String(r.branchId)===String(s.branchId)
        && r.thoiGian instanceof Date && ngay(r.thoiGian)===hnay;
    })
    .map(function(r){
      var items = [];
      try{ items = JSON.parse(r.items || '[]'); }catch(e){}
      var nhanSau = (r.nhanLuc instanceof Date && r.thoiGian instanceof Date)
        ? Math.round((r.nhanLuc - r.thoiGian)/1000) : null;
      return {
        id:String(r.orderId), time: iso(r.thoiGian),
        roomId:String(r.roomId), roomName:String(r.roomName),
        source:String(r.source||''), items:items, note:String(r.ghiChu||''),
        status:String(r.trangThai||'MOI'),
        nghiNgo: (r.nghiNgo===true || r.nghiNgo==='TRUE'),
        lyDo:String(r.lyDoNghiNgo||''),
        pos: (r.daNhapPos===true || r.daNhapPos==='TRUE'),
        nhanSau: nhanSau
      };
    })
    .sort(function(a,b){ return a.time < b.time ? 1 : -1; });

  var ips = doc(SHEETS.IP)
    .filter(function(r){ return String(r.branchId)===String(s.branchId); })
    .map(function(r){
      return {ip:String(r.ip), n:Number(r.soLan||0),
              first: hhmm(r.lanDau)+' '+ngay(r.lanDau),
              last : hhmm(r.lanCuoi)+' '+ngay(r.lanCuoi),
              state:String(r.trangThai||'HOC')};
    });

  return {ok:true, rooms:rooms, orders:orders, ips:ips, serverTime: iso(t)};
}

function apiDsPhong(d){
  var s;
  try{ s = canPhien(d); }catch(e){ return {ok:false, msg:'HET_PHIEN'}; }
  return {ok:true, rooms: doc(SHEETS.PHONG)
    .filter(function(r){ return String(r.branchId)===String(s.branchId); })
    .map(function(r){
      return {roomName:String(r.roomName), ma4:String(r.ma4),
              url: CFG.BASE_URL + '?t=' + r.token};
    })};
}

function apiSetPhong(d){
  var s;
  try{ s = canPhien(d); }catch(e){ return {ok:false, msg:'HET_PHIEN'}; }
  var r = doc(SHEETS.PHONG).filter(function(x){
    return String(x.roomId)===String(d.roomId) && String(x.branchId)===String(s.branchId);
  })[0];
  if(!r) return {ok:false, msg:'KHONG_TIM_THAY'};
  var sp = sh(SHEETS.PHONG);
  sp.getRange(r._row, 6).setValue(d.open ? 'TRUE' : '');
  sp.getRange(r._row, 7).setValue(d.open ? now() : '');
  return {ok:true};
}

function apiXuLyDon(d){
  var s;
  try{ s = canPhien(d); }catch(e){ return {ok:false, msg:'HET_PHIEN'}; }
  var r = doc(SHEETS.DON).filter(function(x){
    return String(x.orderId)===String(d.orderId) && String(x.branchId)===String(s.branchId);
  })[0];
  if(!r) return {ok:false, msg:'KHONG_TIM_THAY'};
  var sd = sh(SHEETS.DON);
  if(d.act==='nhan'){
    sd.getRange(r._row, 11).setValue('NHAN');
    sd.getRange(r._row, 14).setValue(now());
    sd.getRange(r._row, 16).setValue(s.user);
  }else if(d.act==='xong'){
    sd.getRange(r._row, 11).setValue('XONG');
    sd.getRange(r._row, 15).setValue(now());
    sd.getRange(r._row, 16).setValue(s.user);
    dongTicketNeuCan(r);
  }else if(d.act==='huy'){
    sd.getRange(r._row, 11).setValue('HUY');
    sd.getRange(r._row, 15).setValue(now());
    sd.getRange(r._row, 16).setValue(s.user);
  }
  return {ok:true};
}

function apiDanhDauPos(d){
  var s;
  try{ s = canPhien(d); }catch(e){ return {ok:false, msg:'HET_PHIEN'}; }
  var r = doc(SHEETS.DON).filter(function(x){
    return String(x.orderId)===String(d.orderId) && String(x.branchId)===String(s.branchId);
  })[0];
  if(!r) return {ok:false};
  sh(SHEETS.DON).getRange(r._row, 17).setValue('TRUE');
  return {ok:true};
}

function apiChanTb(d){
  var s;
  try{ s = canPhien(d); }catch(e){ return {ok:false, msg:'HET_PHIEN'}; }
  var r = doc(SHEETS.DON).filter(function(x){ return String(x.orderId)===String(d.orderId); })[0];
  if(!r || !r.deviceId) return {ok:false};
  sh(SHEETS.CHAN).appendRow([String(r.deviceId), new Date(Date.now()+24*3600*1000),
    'Chặn bởi ' + s.user + ' từ đơn ' + r.orderId]);
  log('BAO_MAT', 'Chặn thiết bị ' + r.deviceId);
  return {ok:true};
}

/* ============ HỌC IP CHI NHÁNH ============
   Lưu ý phase 1: Apps Script KHÔNG đọc được IP của người gọi, nên IP do
   client tự khai báo — chỉ dùng để quan sát, KHÔNG dùng để chặn.
   Sang phase 2 (Cloudflare Worker) IP lấy từ header CF-Connecting-IP ở
   phía máy chủ, lúc đó mới bật chặn cứng.                                */
function hocIp(branchId, ip){
  var s = sh(SHEETS.IP), rows = doc(SHEETS.IP);
  var t = now();
  var r = rows.filter(function(x){
    return String(x.branchId)===String(branchId) && String(x.ip)===String(ip);
  })[0];
  if(r){
    var n = Number(r.soLan||0) + 1;
    s.getRange(r._row, 3).setValue(n);
    s.getRange(r._row, 5).setValue(t);
    s.getRange(r._row, 6).setValue(n >= CFG.IP_CONG_NHAN ? 'OK' : 'HOC');
  }else{
    s.appendRow([branchId, ip, 1, t, t, 'HOC']);
  }
  /* IP xuất hiện ở nhiều chi nhánh (CGNAT của nhà mạng) → không dùng để kiểm tra */
  var trung = doc(SHEETS.IP).filter(function(x){ return String(x.ip)===String(ip); });
  if(trung.length > 1){
    trung.forEach(function(x){ s.getRange(x._row, 6).setValue('NHAP_NHANG'); });
  }
}
function ipHopLe(branchId, ip){
  var t = now();
  var ok = doc(SHEETS.IP).filter(function(x){
    return String(x.branchId)===String(branchId) && String(x.ip)===String(ip)
      && String(x.trangThai)==='OK'
      && x.lanCuoi instanceof Date && (t - x.lanCuoi) < CFG.IP_HET_HAN_H*3600*1000;
  });
  if(ok.length) return true;
  /* chưa học đủ dữ liệu cho chi nhánh này → không kết luận, coi như hợp lệ */
  var coDuLieu = doc(SHEETS.IP).filter(function(x){
    return String(x.branchId)===String(branchId) && String(x.trangThai)==='OK';
  });
  return coDuLieu.length === 0;
}

/* ============ TELEGRAM ============ */
function tele(branchId, html, themQuanLy){
  if(!CFG.TG_TOKEN) return;
  var cn = chiNhanh(branchId);
  if(!cn) return;
  var ds = [cn.tgChat];
  if(themQuanLy && cn.tgChatQuanLy) ds.push(cn.tgChatQuanLy);
  ds.filter(function(x){ return x; }).forEach(function(chat){
    try{
      UrlFetchApp.fetch('https://api.telegram.org/bot'+CFG.TG_TOKEN+'/sendMessage', {
        method:'post', muteHttpExceptions:true,
        payload:{ chat_id:String(chat), text:html, parse_mode:'HTML' }
      });
    }catch(e){ log('LOI', 'Telegram: '+e); }
  });
}

/* ============ LEO THANG — cài trigger chạy mỗi phút ============
   Telegram là kênh PHỤ. Dashboard quầy vẫn hoạt động 100% nếu Telegram chết. */
function kiemTraLeoThang(){
  var t = now(), sd = sh(SHEETS.DON);
  doc(SHEETS.DON).forEach(function(r){
    if(!(r.thoiGian instanceof Date)) return;
    var st = String(r.trangThai||'MOI');
    if(st==='XONG' || st==='HUY') return;
    if(r.nghiNgo===true || r.nghiNgo==='TRUE') return;   /* đơn nghi ngờ không làm phiền ai */
    var phut = (t - r.thoiGian)/60000;
    var items = [];
    try{ items = JSON.parse(r.items||'[]'); }catch(e){}
    var mota = items.map(function(i){ return i.label + (i.qty>1?' ×'+i.qty:''); }).join(', ');

    if(st==='MOI' && phut >= CFG.SLA1 && !r.baoT1){
      tele(r.branchId, '🔔 <b>'+r.roomName+'</b> chờ '+Math.round(phut)+' phút chưa ai tiếp nhận\n'+mota, false);
      sd.getRange(r._row, 18).setValue('TRUE');
    }
    if(phut >= CFG.SLA2 && !r.baoT2){
      tele(r.branchId, '🚨 <b>'+r.roomName+'</b> QUÁ '+Math.round(phut)+' PHÚT chưa hoàn tất\n'+mota, true);
      sd.getRange(r._row, 19).setValue('TRUE');
    }
  });
}

/* ============ NỐI SANG MODULE BẢO TRÌ ============
   Phòng nào báo cùng một loại sự cố từ 3 lần trở lên trong 7 ngày
   → gửi cảnh báo để mở ticket bảo trì. Chạy 1 lần/ngày bằng trigger.
   (Phase 2: thay phần tele() bằng gọi thẳng API tạo ticket của app BMB.) */
function ngayQuaXetSuCoLapLai(){
  var t = now(), moc = new Date(t.getTime() - 7*24*3600*1000), dem = {};
  doc(SHEETS.DON).forEach(function(r){
    if(!(r.thoiGian instanceof Date) || r.thoiGian < moc) return;
    if(r.nghiNgo===true || r.nghiNgo==='TRUE') return;
    var items = [];
    try{ items = JSON.parse(r.items||'[]'); }catch(e){}
    items.forEach(function(i){
      if(i.type!=='SV' || ['MIC','LANH','NET','PIN'].indexOf(i.code)<0) return;
      var k = r.branchId + '|' + r.roomName + '|' + i.code + '|' + i.label;
      dem[k] = (dem[k]||0) + 1;
    });
  });
  Object.keys(dem).forEach(function(k){
    if(dem[k] < 3) return;
    var p = k.split('|');
    tele(p[0], '🛠 <b>Đề nghị mở ticket bảo trì</b>\nPhòng '+p[1]+' báo "'+p[3]+'" '+dem[k]+' lần trong 7 ngày.', true);
  });
}

function dongTicketNeuCan(r){ /* chỗ móc sang app BMB ở phase 2 */ }

/* ============ ĐÓNG TẤT CẢ PHÒNG CUỐI NGÀY ============
   Cài trigger chạy sau giờ đóng cửa, tránh phòng quên đóng treo qua đêm. */
function dongHetPhong(){
  var s = sh(SHEETS.PHONG), v = s.getDataRange().getValues();
  for(var i=1;i<v.length;i++){
    s.getRange(i+1, 6).setValue('');
    s.getRange(i+1, 7).setValue('');
  }
  log('HE_THONG', 'Đã đóng toàn bộ phòng cuối ngày');
}

/* ============================================================
   ===============  CÁC HÀM CHẠY TAY KHI CÀI ĐẶT  =============
   ============================================================ */

/* B1 — chạy MỘT LẦN để tạo toàn bộ sheet */
function khoiTao(){
  var s = ss();
  var dinh = {};
  dinh[SHEETS.CN]   = ['branchId','ten','tgChat','tgChatQuanLy'];
  dinh[SHEETS.PHONG]= ['branchId','roomId','roomName','token','ma4','open','moLuc'];
  dinh[SHEETS.DON]  = ['orderId','thoiGian','branchId','roomId','roomName','source','deviceId','ip',
                       'items','ghiChu','trangThai','nghiNgo','lyDoNghiNgo','nhanLuc','xongLuc',
                       'nguoiXuLy','daNhapPos','baoT1','baoT2'];
  dinh[SHEETS.TK]   = ['taikhoan','matKhauHash','branchId','vaiTro','saiLienTiep','khoaDen'];
  dinh[SHEETS.IP]   = ['branchId','ip','soLan','lanDau','lanCuoi','trangThai'];
  dinh[SHEETS.CHAN] = ['deviceId','denLuc','lyDo'];
  dinh[SHEETS.LOG]  = ['thoiGian','loai','noiDung'];

  Object.keys(dinh).forEach(function(n){
    var sheet = s.getSheetByName(n) || s.insertSheet(n);
    if(sheet.getLastRow() === 0){
      sheet.appendRow(dinh[n]);
      sheet.getRange(1,1,1,dinh[n].length).setFontWeight('bold').setBackground('#1b1b28').setFontColor('#ffffff');
      sheet.setFrozenRows(1);
    }
  });
  /* cột giờ trong sheet PHONG dễ bị Sheets tự đổi định dạng — ép kiểu ngày giờ */
  sh(SHEETS.PHONG).getRange('G2:G').setNumberFormat('yyyy-mm-dd hh:mm');
  Logger.log('Đã tạo xong các sheet. Bước tiếp theo: chạy taoChiNhanh().');
}

/* B2 — tạo 1 chi nhánh kèm N phòng. Sửa tham số rồi bấm Run. */
function taoChiNhanh(){
  var branchId = 'CN01';
  var ten      = 'Boom Music Box — Chi nhánh thử nghiệm';
  var soPhong  = 15;
  var tgChat   = '';   /* chat_id group Telegram của chi nhánh, để trống cũng được */
  var tgQuanLy = '';

  if(chiNhanh(branchId)) throw new Error('Chi nhánh ' + branchId + ' đã tồn tại.');
  sh(SHEETS.CN).appendRow([branchId, ten, tgChat, tgQuanLy]);

  var daCo = {};
  doc(SHEETS.PHONG).forEach(function(r){ daCo[String(r.ma4)] = 1; });

  var rows = [];
  for(var i=1;i<=soPhong;i++){
    var ma4;
    do { ma4 = String(Math.floor(1000 + Math.random()*9000)); } while(daCo[ma4]);
    daCo[ma4] = 1;
    rows.push([branchId, branchId+'-P'+i, 'P'+i, rid(10), ma4, '', '']);
  }
  sh(SHEETS.PHONG).getRange(sh(SHEETS.PHONG).getLastRow()+1, 1, rows.length, 7).setValues(rows);
  Logger.log('Đã tạo ' + soPhong + ' phòng cho ' + branchId + '. Vào tab "In mã QR" trên quay.html để in.');
}

/* B3 — tạo tài khoản quầy. Sửa tham số rồi bấm Run. Chạy xong XOÁ mật khẩu khỏi code. */
function taoTaiKhoan(){
  var taikhoan = 'cn01';
  var matKhau  = 'DoiMatKhauNgay!';
  var branchId = 'CN01';
  var vaiTro   = 'QUAY';       /* QUAY hoặc ADMIN */

  var u = taikhoan.trim().toLowerCase();
  if(doc(SHEETS.TK).filter(function(r){ return String(r.taikhoan).toLowerCase()===u; }).length)
    throw new Error('Tài khoản đã tồn tại — dùng doiMatKhau() thay vì tạo mới.');
  var h = sha256Hex(CFG.SECRET + '|' + sha256Hex(CFG.SALT + '|' + u + '|' + matKhau));
  sh(SHEETS.TK).appendRow([u, h, branchId, vaiTro, 0, '']);
  Logger.log('Đã tạo tài khoản ' + u + '. Hãy xoá mật khẩu khỏi code ngay.');
}

/* B4 — đổi mật khẩu (tài khoản phải đã tồn tại, dùng đúng tên MỚI nếu vừa đổi tên) */
function doiMatKhau(){
  var taikhoan = 'cn01';
  var matKhauMoi = 'MatKhauMoi!123';

  var u = taikhoan.trim().toLowerCase();
  var r = doc(SHEETS.TK).filter(function(x){ return String(x.taikhoan).toLowerCase()===u; })[0];
  if(!r) throw new Error('Không tìm thấy tài khoản "'+u+'". Đổi tên trong Sheet trước thì phải dùng tên MỚI.');
  var h = sha256Hex(CFG.SECRET + '|' + sha256Hex(CFG.SALT + '|' + u + '|' + matKhauMoi));
  sh(SHEETS.TK).getRange(r._row, 2).setValue(h);
  sh(SHEETS.TK).getRange(r._row, 5).setValue(0);
  sh(SHEETS.TK).getRange(r._row, 6).setValue('');
  Logger.log('Đã đổi mật khẩu cho ' + u + '. Hãy xoá mật khẩu khỏi code ngay.');
}

/* B5 — cài trigger tự động. Chạy MỘT LẦN. */
function caiTrigger(){
  ScriptApp.getProjectTriggers().forEach(function(t){ ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('kiemTraLeoThang').timeBased().everyMinutes(1).create();
  ScriptApp.newTrigger('ngayQuaXetSuCoLapLai').timeBased().everyDays(1).atHour(9).create();
  ScriptApp.newTrigger('dongHetPhong').timeBased().everyDays(1).atHour(4).create();
  Logger.log('Đã cài 3 trigger.');
}

/* B0 — chạy hàm này TRƯỚC TIÊN để Google hiện hộp thoại xin quyền UrlFetch
   (nếu chạy hàm nghiệp vụ mà nó lỗi sớm, hộp thoại quyền sẽ không bao giờ bật ra) */
function xinQuyen(){
  UrlFetchApp.fetch('https://www.google.com');
  Logger.log('Đã có quyền UrlFetch. Giờ chạy được khoiTao().');
}
