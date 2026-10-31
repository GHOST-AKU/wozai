#!/usr/bin/env python3
"""Generate native UI aliases from the shared five-language source catalogs."""
import os
from pathlib import Path
os.chdir(Path(__file__).resolve().parents[2])
from pathlib import Path
import json
aliases={
'actionFailed':'error','address':'endpoint','appearance':'appearanceSection','approvalBody':'privacy','approveConnection':'requestTitle','approveOnce':'once','attachFiles':'sendFile','batchQueue':'attachments','bluetoothBody':'androidBluetoothHint','clearHistory':'clearConversation','clearHistoryBody':'clearHistoryWarning','composerHint':'androidComposer','connected':'connectedIndicator','connectionStatus':'connectionsSection','copied':'addressCopied','copy':'copyCode','dataBody':'historyTrustRetained','dataFolder':'dataDirectory','deviceDetails':'deviceInfo','diagnostics':'pathDetails','directConnect':'direct','discoveredDevices':'nearbyDevices','discoveryNotTrust':'privacy','editAvatar':'avatarEdit','emptyQueue':'noMessages','encrypted':'identityEncrypted','enterToSend':'enterSend','file':'sendFile','fileReviewBody':'attachmentHintV2','identity':'deviceInfo','lanBody':'lanNoDevicesBody','myAddresses':'myAddress','myProfile':'deviceSection','newConnection':'newChat','noFileApp':'attachmentSaveBeforeOpening','noNearby':'noDevicesTitle','noTrusted':'noTrustedDevices','off':'notStarted','offlineHistory':'localHistoryDisconnected','on':'receiving','openFolder':'attachmentShowInFolder','privacyStorage':'privacy','publicKey':'deviceInfo','quota':'attachmentQuota','readReceiptsBody':'readReceiptsSummary','receive':'attachmentAccept','receiveDiscover':'connectionsSection','refresh':'refreshHint','rememberApprove':'remember','remove':'attachmentRemove','replacementBody':'attachmentRetrySelectAgain','reselect':'attachmentReselect','retry':'attachmentRetry','retryBatch':'attachmentRetryBatch','revoked':'trustRevokedReconnect','saveAs':'attachmentSaveAs','search':'searchDevices','selectedFiles':'attachmentPreview','sessionChanged':'verificationConnectionChanged','showInFolder':'attachmentShowInFolder','trustedDevice':'trustedConnect','unsafeOpen':'attachmentSaveBeforeOpening','viewImage':'attachmentOpen','welcome':'noChatsTitle','welcomeBody':'noChatsBody','normal':'normalText','large':'largeText','largest':'extraLargeText','light':'lightTheme','dark':'darkTheme','accept':'attachmentAccept','reject':'attachmentReject','pause':'attachmentPause','resume':'attachmentResume','cancel':'cancel','open':'attachmentOpen','noConversations':'noChatsTitle','noResults':'noSearchResultsTitle','conversationStart':'noMessages','searchMessages':'searchChats',
'security':'verificationTitle','verifyExplain':'verificationExplain','verified':'identityVerified','unverified':'identityUnverified','markVerified':'verificationMark','verifyConfirm':'verificationConfirm','readQr':'verificationImage','saveQr':'verificationSaveQr','copyCode':'verificationCopy','verificationDone':'verificationDone','qrMismatch':'verificationMismatch','connectForVerification':'verificationConnectFirst','resetAvatar':'avatarReset','choosePhoto':'avatarChoose','avatarCrop':'avatarCrop','revokeConfirm':'revokeConfirm','more':'moreActions','textIncomplete':'messageIncomplete',
}
rows='''privateLocal|仅在设备之间 · 记录保存在本机|Between devices · Stored locally|僅在裝置之間 · 記錄儲存在本機|端末間の通信 · 履歴はローカルに保存|기기 간 통신 · 로컬에 기록 저장
nearbySummary|找到身边的设备，直接开始聊天。|Find a nearby device and start a direct conversation.|找到身邊的裝置，直接開始聊天。|近くの端末を見つけて会話を始めましょう。|근처 기기를 찾아 대화를 시작하세요.
settingsSummary|让这台设备用起来更顺手。|Make this device feel like yours.|讓這台裝置用起來更順手。|この端末を使いやすく設定します。|이 기기를 편리하게 설정하세요.
independentIdentity|此实验版独立保存身份与记录|Separate identity and history for this experiment|此實驗版獨立儲存身分與記錄|実験版の ID と履歴は個別に保存されます|실험 버전의 ID와 기록은 별도로 저장됩니다
aboutBody|WinUI 3 原生桌面实验版。无需账号和远程服务器。聊天记录和附件保存在本机，传输使用端到端加密。|Native WinUI 3 desktop experiment. No account or remote server. History and files stay on this device; transfers are end-to-end encrypted.|WinUI 3 原生桌面實驗版。無需帳號和遠端伺服器。聊天記錄和附件儲存在本機，傳輸使用端對端加密。|WinUI 3 ネイティブ実験版。アカウントやサーバーは不要です。履歴は端末内に保存し、通信はエンドツーエンドで暗号化します。|WinUI 3 네이티브 실험 버전. 계정과 서버가 필요하지 않습니다. 기록은 기기에 저장되고 통신은 종단간 암호화됩니다.
helpBody|让两台设备加入同一个 Wi-Fi 或热点，在附近开启局域网接收，再搜索或输入对方的地址和端口。蓝牙需要双方开启接收并完成系统配对。首次连接请核对设备身份。|Join the same Wi-Fi or hotspot, enable LAN reception in Nearby, then search or enter the other device address and port. Bluetooth requires reception and system pairing. Check the peer identity on first contact.|讓兩台裝置加入同一個 Wi-Fi 或熱點，開啟區域網路接收，再搜尋或輸入對方位址和連接埠。藍牙需要雙方開啟接收並完成系統配對。首次連線請核對身分。|同じ Wi-Fi に接続し、近くで LAN 受信を開始して検索するか相手のアドレスを入力してください。Bluetooth は受信とペアリングが必要です。初回は ID を確認してください。|같은 Wi-Fi에 연결하고 근처에서 LAN 수신을 켠 뒤 검색하거나 상대 주소를 입력하세요. Bluetooth는 수신과 페어링이 필요합니다. 최초 연결 시 ID를 확인하세요.
loadOlder|加载更早消息|Load earlier messages|載入更早訊息|以前のメッセージを読み込む|이전 메시지 불러오기
avatarCropHint|拖动缩放与位置，预览后再保存。|Adjust zoom and position, then save the preview.|調整縮放與位置，預覽後再儲存。|拡大率と位置を調整してから保存します。|확대 및 위치를 조절한 뒤 저장하세요.
zoom|缩放|Zoom|縮放|拡大率|확대
horizontal|水平位置|Horizontal position|水平位置|水平位置|가로 위치
vertical|垂直位置|Vertical position|垂直位置|垂直位置|세로 위치
'''
custom={}
for row in rows.strip().splitlines():
 key,*values=row.split('|');custom[key]=values
langs=['zh-Hans','en','zh-Hant','ja','ko']; catalogs={l:json.load(open('i18n/messages/'+l+'.json')) for l in langs}
result={}
for i,l in enumerate(langs):
 d=dict(catalogs[l])
 for key,alias in aliases.items():
  if alias in catalogs[l] and '{' not in catalogs[l][alias]:d[key]=catalogs[l][alias]
 for key,values in custom.items():d[key]=values[i]
 result[l]=d
# Remaining short aliases with exact shared meaning, explicitly translated in all five locales.
extra={'copy':['复制','Copy','複製','コピー','복사'],'dataFolder':['本地数据目录','Local data folder','本機資料目錄','ローカルデータ','로컬 데이터 폴더'],'refresh':['刷新','Refresh','重新整理','更新','새로 고침'],'normal':['标准','Normal','標準','標準','보통'],'large':['较大','Large','較大','大','크게'],'largest':['最大','Largest','最大','最大','가장 크게'],'enterToSend':['Enter 发送 · Shift+Enter 换行','Enter to send · Shift+Enter for a new line','Enter 傳送 · Shift+Enter 換行','Enter で送信 · Shift+Enter で改行','Enter 전송 · Shift+Enter 줄 바꿈'],'connectionStatus':['连接状态','Connection status','連線狀態','接続状態','연결 상태'],'publicKey':['设备公钥','Device public key','裝置公鑰','端末の公開鍵','기기 공개 키'],'resetAvatar':['重置头像','Reset avatar','重設頭像','画像をリセット','아바타 초기화'],'choosePhoto':['选择照片','Choose photo','選擇照片','写真を選ぶ','사진 선택'],'avatarCrop':['裁剪头像','Crop avatar','裁剪頭像','画像を切り抜く','아바타 자르기'],'encrypted':['端到端加密','End-to-end encrypted','端對端加密','エンドツーエンド暗号化','종단간 암호화'],'statusIdle':['未连接','Not connected','未連線','未接続','연결되지 않음']}
extra['diagnostics']=['连接与传输诊断','Connection and transfer diagnostics','連線與傳輸診斷','接続と転送の診断','연결 및 전송 진단']
extra['privacyStorage']=['隐私与存储','Privacy and storage','隱私與儲存','プライバシーと保存','개인정보 및 저장']
extra['editAvatar']=['编辑头像','Edit avatar','編輯頭像','アバターを編集','아바타 편집']
for key,values in extra.items():
 for i,l in enumerate(langs):result[l][key]=values[i]
Path('windows-next/WindowsNext/UiCatalog.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
