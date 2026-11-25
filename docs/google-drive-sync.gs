/**
 * MyRunSync -> Google Drive 자동 동기화용 Google Apps Script 브리지
 *
 * [설치 및 배포 방법 (최초 1회, 약 3분)]
 * 1. https://script.google.com 접속 후 [새 프로젝트]를 생성합니다.
 * 2. 편집기의 기본 코드를 모두 지우고, 이 파일 내용을 그대로 붙여넣습니다.
 * 3. (선택) 보안 시크릿을 사용하려면 아래 SHARED_SECRET 값에 원하는 비밀 문자열을 입력합니다.
 *    - MyRunSync 앱의 Google Drive 설정에서도 동일한 시크릿을 입력해야 합니다.
 * 4. 우측 상단 [배포] -> [새 배포]를 클릭합니다.
 *    - 유형 선택: [웹 앱]
 *    - 다음 사용자 권한으로 실행: [나(본인 계정)]
 *    - 액세스 권한이 있는 사용자: [모든 사용자]
 *      (안드로이드 앱/WorkManager가 Google OAuth 로그인 없이 백그라운드에서 직접 전송하므로 필요합니다.
 *       URL이 길고 복잡하며 SHARED_SECRET으로 요청을 검증할 수 있습니다.)
 * 5. [배포] 클릭 후 권한 검토/허용을 진행하고, 생성된 "웹 앱 URL(https://script.google.com/macros/s/.../exec)"을
 *    MyRunSync 앱의 [Google Drive 백업(Gemini용)] 카드에 붙여넣어 저장합니다.
 *
 * [생성되는 파일 (내 드라이브 > MyRun 폴더)]
 * - MyRun_Running_Logs (Google 스프레드시트): 전체 러닝 누적 이력 (hc_session_id 기준 중복 없이 업데이트)
 * - MyRun_Gemini_Coach_Data (Google 문서): 제미나이 웹 / Gem 지식 연결에 최적화된 최근 30회 요약 + 최근 5회 1분 단위 데이터
 */

var FOLDER_NAME = 'MyRun';
var SHEET_NAME = 'MyRun_Running_Logs';
var DOC_NAME = 'MyRun_Gemini_Coach_Data';
var SHARED_SECRET = ''; // 선택 사항: 비워 두면 시크릿 검증 없이 허용, 값을 넣으면 MyRunSync 시크릿과 일치해야 함

function doGet(e) {
  return jsonResponse({
    ok: true,
    service: 'MyRun Google Drive Sync',
    folder: FOLDER_NAME,
    sheet: SHEET_NAME,
    doc: DOC_NAME
  });
}

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({ ok: false, error: 'Empty request body' });
    }

    var payload = JSON.parse(e.postData.contents);
    if (SHARED_SECRET && payload.secret !== SHARED_SECRET) {
      return jsonResponse({ ok: false, error: 'Unauthorized: invalid secret' });
    }

    var logs = Array.isArray(payload.logs) ? payload.logs : [];
    var folder = getOrCreateFolder_(FOLDER_NAME);
    var sheetFile = getOrCreateSpreadsheet_(folder, SHEET_NAME);
    var allRecords = upsertLogsToSheet_(sheetFile, logs);

    var docFile = getOrCreateDoc_(folder, DOC_NAME);
    updateGeminiDoc_(docFile, allRecords, payload);

    return jsonResponse({
      ok: true,
      upserted: logs.length,
      total: allRecords.length,
      sheetUrl: sheetFile.getUrl(),
      docUrl: docFile.getUrl()
    });
  } catch (err) {
    return jsonResponse({
      ok: false,
      error: err && err.message ? err.message : String(err)
    });
  }
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

function getOrCreateFolder_(name) {
  var it = DriveApp.getFoldersByName(name);
  if (it.hasNext()) return it.next();
  return DriveApp.createFolder(name);
}

function getOrCreateSpreadsheet_(folder, name) {
  var it = folder.getFilesByName(name);
  while (it.hasNext()) {
    var f = it.next();
    if (f.getMimeType() === MimeType.GOOGLE_SHEETS) {
      return SpreadsheetApp.openById(f.getId());
    }
  }
  var ss = SpreadsheetApp.create(name);
  var file = DriveApp.getFileById(ss.getId());
  file.moveTo(folder);
  return ss;
}

function getOrCreateDoc_(folder, name) {
  var it = folder.getFilesByName(name);
  while (it.hasNext()) {
    var f = it.next();
    if (f.getMimeType() === MimeType.GOOGLE_DOCS) {
      return DocumentApp.openById(f.getId());
    }
  }
  var doc = DocumentApp.create(name);
  var file = DriveApp.getFileById(doc.getId());
  file.moveTo(folder);
  return doc;
}

function upsertLogsToSheet_(spreadsheet, incomingLogs) {
  var sheet = spreadsheet.getSheets()[0];
  var headers = [
    'hc_session_id',
    'run_date',
    'workout_type',
    'exercise_type',
    'has_gps',
    'distance_km',
    'duration_sec',
    'avg_pace_sec',
    'avg_hr',
    'max_hr',
    'min_hr',
    'zone2_pct',
    'avg_cadence',
    'max_cadence',
    'calories',
    'altitude_gain',
    'vo2_max',
    'title',
    'timeseries_json'
  ];

  var recordMap = {};
  var lastRow = sheet.getLastRow();
  var lastCol = sheet.getLastColumn();
  if (lastRow >= 2 && lastCol >= 1) {
    var existingValues = sheet.getRange(1, 1, lastRow, Math.max(lastCol, headers.length)).getValues();
    var oldHeaders = existingValues[0];
    var colIndex = {};
    for (var c = 0; c < oldHeaders.length; c++) {
      colIndex[String(oldHeaders[c]).trim()] = c;
    }
    for (var r = 1; r < existingValues.length; r++) {
      var row = existingValues[r];
      var idVal = row[colIndex['hc_session_id']];
      if (!idVal) continue;
      var rec = {};
      for (var h = 0; h < headers.length; h++) {
        var key = headers[h];
        var idx = colIndex[key];
        rec[key] = idx !== undefined ? row[idx] : '';
      }
      if (rec.timeseries_json && typeof rec.timeseries_json === 'string') {
        try {
          rec.timeseries_1min = JSON.parse(rec.timeseries_json);
        } catch (parseErr) {
          rec.timeseries_1min = [];
        }
      } else {
        rec.timeseries_1min = [];
      }
      recordMap[String(idVal)] = rec;
    }
  }

  for (var i = 0; i < incomingLogs.length; i++) {
    var item = incomingLogs[i];
    if (!item || !item.hc_session_id) continue;
    var ts = Array.isArray(item.timeseries_1min) ? item.timeseries_1min : [];
    recordMap[String(item.hc_session_id)] = {
      hc_session_id: String(item.hc_session_id),
      run_date: item.run_date || '',
      workout_type: item.workout_type || '',
      exercise_type: item.exercise_type || '',
      has_gps: item.has_gps === true ? 'true' : (item.has_gps === false ? 'false' : ''),
      distance_km: item.distance_km !== undefined && item.distance_km !== null ? Number(item.distance_km) : 0,
      duration_sec: item.duration_sec !== undefined && item.duration_sec !== null ? Number(item.duration_sec) : 0,
      avg_pace_sec: item.avg_pace_sec !== undefined && item.avg_pace_sec !== null ? Number(item.avg_pace_sec) : '',
      avg_hr: item.avg_hr !== undefined && item.avg_hr !== null ? Number(item.avg_hr) : '',
      max_hr: item.max_hr !== undefined && item.max_hr !== null ? Number(item.max_hr) : '',
      min_hr: item.min_hr !== undefined && item.min_hr !== null ? Number(item.min_hr) : '',
      zone2_pct: item.zone2_pct !== undefined && item.zone2_pct !== null ? Number(item.zone2_pct) : '',
      avg_cadence: item.avg_cadence !== undefined && item.avg_cadence !== null ? Number(item.avg_cadence) : '',
      max_cadence: item.max_cadence !== undefined && item.max_cadence !== null ? Number(item.max_cadence) : '',
      calories: item.calories !== undefined && item.calories !== null ? Number(item.calories) : '',
      altitude_gain: item.altitude_gain !== undefined && item.altitude_gain !== null ? Number(item.altitude_gain) : '',
      vo2_max: item.vo2_max !== undefined && item.vo2_max !== null ? Number(item.vo2_max) : '',
      title: item.title || '',
      timeseries_1min: ts
    };
  }

  var allRecords = Object.keys(recordMap).map(function (k) { return recordMap[k]; });
  allRecords.sort(function (a, b) {
    return String(b.run_date || '').localeCompare(String(a.run_date || ''));
  });

  var rows = [headers];
  for (var j = 0; j < allRecords.length; j++) {
    var recOut = allRecords[j];
    // Keep 1-minute timeseries JSON only on recent 30 rows to keep sheet compact
    var tsJson = (j < 30 && Array.isArray(recOut.timeseries_1min) && recOut.timeseries_1min.length > 0)
      ? JSON.stringify(recOut.timeseries_1min)
      : '';
    rows.push([
      recOut.hc_session_id,
      recOut.run_date,
      recOut.workout_type,
      recOut.exercise_type,
      recOut.has_gps,
      recOut.distance_km,
      recOut.duration_sec,
      recOut.avg_pace_sec,
      recOut.avg_hr,
      recOut.max_hr,
      recOut.min_hr,
      recOut.zone2_pct,
      recOut.avg_cadence,
      recOut.max_cadence,
      recOut.calories,
      recOut.altitude_gain,
      recOut.vo2_max,
      recOut.title,
      tsJson
    ]);
  }

  sheet.clearContents();
  sheet.getRange(1, 1, rows.length, headers.length).setValues(rows);
  sheet.setFrozenRows(1);

  return allRecords;
}

function updateGeminiDoc_(doc, allRecords, payload) {
  var body = doc.getBody();
  body.clear();

  var syncedAt = payload.synced_at || new Date().toISOString();
  var z2Min = payload.zone2_min_bpm || 114;
  var z2Max = payload.zone2_max_bpm || 133;

  body.appendParagraph('MyRun 러닝 & 운동 코칭 데이터 (Gemini용 자동 동기화)')
    .setHeading(DocumentApp.ParagraphHeading.HEADING1);

  body.appendParagraph(
    '최종 동기화: ' + formatDateTimeKst_(syncedAt) +
    ' | Zone 2 기준: ' + z2Min + '~' + z2Max + ' bpm' +
    ' | 누적 기록 수: ' + allRecords.length + '회'
  );

  body.appendParagraph('최근 30회 운동 요약')
    .setHeading(DocumentApp.ParagraphHeading.HEADING2);

  var recent30 = allRecords.slice(0, 30);
  if (recent30.length === 0) {
    body.appendParagraph('기록이 없습니다.');
  } else {
    for (var i = 0; i < recent30.length; i++) {
      var r = recent30[i];
      var line = (i + 1) + '. ' + formatDateTimeKst_(r.run_date) +
        ' [' + (r.workout_type || '-') + (r.exercise_type ? '/' + r.exercise_type : '') + '] ' +
        Number(r.distance_km || 0).toFixed(2) + 'km, ' +
        formatDuration_(r.duration_sec) + ', ' +
        '페이스 ' + formatPaceWithSpeed_(r.avg_pace_sec) + ', ' +
        '평균심박 ' + (r.avg_hr !== '' ? r.avg_hr + 'bpm' : '-') +
        (r.max_hr !== '' ? '(최고 ' + r.max_hr + ')' : '') + ', ' +
        'Zone2 ' + (r.zone2_pct !== '' ? Number(r.zone2_pct).toFixed(1) + '%' : '-') + ', ' +
        '케이던스 ' + (r.avg_cadence !== '' ? r.avg_cadence + 'spm' : '-') +
        (r.calories !== '' ? ', ' + r.calories + 'kcal' : '') +
        (r.altitude_gain !== '' && Number(r.altitude_gain) > 0 ? ', 상승 +' + Math.round(r.altitude_gain) + 'm' : '') +
        (r.vo2_max !== '' ? ', VO2Max ' + r.vo2_max : '');
      body.appendParagraph(line);
    }
  }

  body.appendParagraph('최근 5회 1분 단위 상세 시계열 (timeseries_1min)')
    .setHeading(DocumentApp.ParagraphHeading.HEADING2);

  var recentWithTs = allRecords.filter(function (rec) {
    return Array.isArray(rec.timeseries_1min) && rec.timeseries_1min.length > 0;
  }).slice(0, 5);

  if (recentWithTs.length === 0) {
    body.appendParagraph('1분 단위 상세 데이터가 없습니다.');
  } else {
    for (var j = 0; j < recentWithTs.length; j++) {
      var run = recentWithTs[j];
      body.appendParagraph(
        formatDateTimeKst_(run.run_date) + ' · ' + (run.workout_type || '-') +
        ' (' + Number(run.distance_km || 0).toFixed(2) + 'km, ' + formatPaceWithSpeed_(run.avg_pace_sec) + ')'
      ).setHeading(DocumentApp.ParagraphHeading.HEADING3);

      var tsLines = run.timeseries_1min.map(function (pt) {
        return pt.minute + '분: ' +
          '심박 ' + (pt.hr !== undefined && pt.hr !== null ? pt.hr + 'bpm' : '-') +
          ', 페이스 ' + formatPaceShort_(pt.pace_sec) +
          (pt.cadence !== undefined && pt.cadence !== null ? ', 케이던스 ' + pt.cadence + 'spm' : '') +
          (pt.distance_m !== undefined && pt.distance_m !== null ? ', 거리 ' + Math.round(pt.distance_m) + 'm' : '') +
          (pt.zone2_ratio !== undefined && pt.zone2_ratio !== null ? ', Z2 ' + Math.round(Number(pt.zone2_ratio) * 100) + '%' : '');
      });
      body.appendParagraph(tsLines.join('\n'));
    }
  }

  doc.saveAndClose();
}

function formatDateTimeKst_(iso) {
  if (!iso) return '-';
  var d = new Date(iso);
  if (isNaN(d.getTime())) return String(iso);
  return Utilities.formatDate(d, 'Asia/Seoul', 'yyyy-MM-dd HH:mm');
}

function formatDuration_(sec) {
  var s = Number(sec);
  if (!isFinite(s) || s <= 0) return '-';
  var h = Math.floor(s / 3600);
  var m = Math.floor((s % 3600) / 60);
  var r = Math.floor(s % 60);
  if (h > 0) return h + '시간 ' + m + '분';
  return m + '분 ' + r + '초';
}

function formatPaceShort_(secPerKm) {
  var s = Number(secPerKm);
  if (!isFinite(s) || s <= 0) return '-';
  var m = Math.floor(s / 60);
  var r = Math.round(s % 60);
  if (r === 60) {
    m += 1;
    r = 0;
  }
  return m + "'" + (r < 10 ? '0' + r : r) + '"';
}

function formatPaceWithSpeed_(secPerKm) {
  var s = Number(secPerKm);
  if (!isFinite(s) || s <= 0) return '-';
  var kmh = (3600 / s).toFixed(1);
  return formatPaceShort_(s) + '/km (' + kmh + ' km/h)';
}
