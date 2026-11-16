package app.runmate.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 한국어/영어 외 언어의 번역표. 영어 문구(자리표시자 {0},{1}…)를 열쇠로 찾는다.
 * 이 파일은 번역 데이터에서 생성된 것으로, 순서는 LANGS와 같다.
 */
object I18n {
    /** 번역표의 열 순서 */
    val LANGS = listOf("zh", "ja", "es", "pt", "de", "fr", "ru", "id")
    /** 설정 화면에 보여줄 지원 언어 (코드, 그 언어로 쓴 이름) */
    val SUPPORTED = listOf("ko" to "한국어", "en" to "English", "zh" to "中文 (简体)", "ja" to "日本語", "es" to "Español",
        "pt" to "Português", "de" to "Deutsch", "fr" to "Français", "ru" to "Русский", "id" to "Bahasa Indonesia")

    /** 휴대폰 언어 코드를 지원 언어로 맞춘다. 지원하지 않으면 영어. */
    fun resolve(phoneLang: String): String {
        val l = phoneLang.lowercase()
        return when {
            l == "in" -> "id"            // 인도네시아어의 옛 코드
            SUPPORTED.any { it.first == l } -> l
            else -> "en"
        }
    }

    private val exact = HashMap<String, Array<String>>()
    private val patterns = ArrayList<Triple<Regex, List<Int>, Array<String>>>()
    private val cache = ConcurrentHashMap<String, String>()

    private fun add(en: String, vararg t: String) {
        val arr = arrayOf(*t)
        if (!en.contains("{0}")) { exact[en] = arr; return }
        // 자리표시자가 있는 문구: 실제 값이 채워진 영어 문장에서 값을 뽑아 번역문에 다시 넣는다
        val order = ArrayList<Int>()
        val sb = StringBuilder()
        var i = 0
        while (i < en.length) {
            if (en[i] == '{' && i + 2 < en.length && en[i + 1].isDigit() && en[i + 2] == '}') { order.add(en[i + 1] - '0'); sb.append("(.*?)"); i += 3 }
            else { val j = en.indexOf('{', i + 1).let { if (it < 0) en.length else it }; sb.append(Regex.escape(en.substring(i, j))); i = j }
        }
        patterns.add(Triple(Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL), order, arr))
    }

    fun translate(lang: String, en: String): String {
        val col = LANGS.indexOf(lang)
        if (col < 0) return en
        val key = "$col|$en"
        cache[key]?.let { return it }
        var out = exact[en]?.get(col)
        if (out == null) for ((re, order, arr) in patterns) {
            val m = re.matchEntire(en) ?: continue
            var s = arr[col]
            for ((g, n) in order.withIndex()) s = s.replace("{$n}", m.groupValues[g + 1])
            out = s; break
        }
        val res = out ?: en
        if (cache.size > 3000) cache.clear()
        cache[key] = res
        return res
    }

    /** 번역표에 있는 영어 열쇠 전체 (테스트용) */
    fun keys(): List<String> = exact.keys.toList() + patterns.map { it.first.pattern }
    fun size(): Int = exact.size + patterns.size

    init {
        part0()
        part1()
        part2()
        part3()
        part4()
        part5()
        part6()
    }

    private fun part0() {
        add("Runs are encrypted with the group key and delivered through public relay servers (ntfy.sh, Nostr relays). The servers can't read them, and only coordinates relative to your start point are shared.",
            "记录使用群组密钥加密，经公共中继服务器（ntfy.sh、Nostr 中继）传递。服务器无法查看内容，位置只分享相对起点的坐标。", "記録はグループキーで暗号化され、公開中継サーバー（ntfy.sh、Nostrリレー）経由で届けられます。サーバーは内容を見られず、位置はスタート地点からの相対座標のみ共有されます。", "Las carreras se cifran con la clave del grupo y se entregan mediante servidores públicos de retransmisión (ntfy.sh, relés Nostr). Los servidores no pueden leerlas y solo se comparten coordenadas relativas a tu punto de salida.", "As corridas são criptografadas com a chave do grupo e entregues por servidores públicos de retransmissão (ntfy.sh, relays Nostr). Os servidores não conseguem lê-las e só são compartilhadas coordenadas relativas ao seu ponto de partida.", "Läufe werden mit dem Gruppenschlüssel verschlüsselt und über öffentliche Relay-Server (ntfy.sh, Nostr-Relays) zugestellt. Die Server können sie nicht lesen, und es werden nur Koordinaten relativ zu deinem Startpunkt geteilt.", "Les courses sont chiffrées avec la clé du groupe et transmises via des serveurs relais publics (ntfy.sh, relais Nostr). Les serveurs ne peuvent pas les lire et seules des coordonnées relatives à votre point de départ sont partagées.", "Пробежки шифруются ключом группы и передаются через публичные серверы-ретрансляторы (ntfy.sh, реле Nostr). Серверы не могут их прочитать, а передаются только координаты относительно точки старта.", "Lari dienkripsi dengan kunci grup dan dikirim melalui server relai publik (ntfy.sh, relai Nostr). Server tidak dapat membacanya, dan hanya koordinat relatif terhadap titik awal yang dibagikan.")
        add("Removing someone changes the group's encryption key, and only the remaining members get the new key. The removed person can't see later runs (runs they already received stay on their phone).",
            "移除成员后，群组的加密密钥会更换，新密钥只发给留下的成员。被移除的人无法查看之后的记录（已收到的记录仍留在其手机上）。", "メンバーを外すとグループの暗号キーが新しくなり、残ったメンバーにだけ新しいキーが届きます。外された人は以降の記録を見られません（すでに受け取った記録はその人の端末に残ります）。", "Al eliminar a alguien cambia la clave de cifrado del grupo y solo los miembros restantes reciben la nueva. La persona eliminada no podrá ver las carreras posteriores (las que ya recibió quedan en su teléfono).", "Ao remover alguém, a chave de criptografia do grupo muda e só os membros restantes recebem a nova. A pessoa removida não verá as corridas seguintes (as que já recebeu ficam no celular dela).", "Beim Entfernen ändert sich der Verschlüsselungsschlüssel der Gruppe, und nur die verbleibenden Mitglieder erhalten den neuen. Die entfernte Person sieht spätere Läufe nicht mehr (bereits empfangene Läufe bleiben auf ihrem Telefon).", "Retirer quelqu'un change la clé de chiffrement du groupe, et seuls les membres restants reçoivent la nouvelle. La personne retirée ne verra plus les courses suivantes (celles déjà reçues restent sur son téléphone).", "При удалении участника ключ шифрования группы меняется, и новый ключ получают только оставшиеся. Удалённый участник не увидит последующие пробежки (уже полученные останутся на его телефоне).", "Mengeluarkan seseorang akan mengganti kunci enkripsi grup, dan hanya anggota yang tersisa yang menerima kunci baru. Orang yang dikeluarkan tidak dapat melihat lari berikutnya (lari yang sudah diterima tetap ada di ponselnya).")
        add("Your friend joins by scanning this in RunMate: Groups tab → 'Scan QR'. This QR contains the group's encryption key, so show it only to people you want in the group.",
            "朋友在 RunMate 的“群组”标签页 →“扫描二维码”中扫描此码即可加入。此二维码包含群组的加密密钥，请只向要一起的人出示。", "友だちがRunMateの「グループ」タブ →「QRスキャン」でこのコードを読み取ると参加できます。このQRにはグループの暗号キーが含まれているので、一緒に使う人にだけ見せてください。", "Tu amigo se une escaneando esto en RunMate: pestaña Grupos → 'Escanear QR'. Este QR contiene la clave de cifrado del grupo, así que muéstralo solo a quien quieras en el grupo.", "Seu amigo entra lendo isto no RunMate: aba Grupos → 'Ler QR'. Este QR contém a chave de criptografia do grupo, então mostre-o apenas a quem você quer no grupo.", "Dein Freund tritt bei, indem er dies in RunMate scannt: Tab Gruppen → 'QR scannen'. Dieser QR enthält den Verschlüsselungsschlüssel der Gruppe – zeige ihn nur Personen, die in die Gruppe sollen.", "Votre ami rejoint le groupe en scannant ceci dans RunMate : onglet Groupes → « Scanner le QR ». Ce QR contient la clé de chiffrement du groupe ; ne le montrez qu'aux personnes que vous voulez dans le groupe.", "Друг вступит в группу, отсканировав этот код в RunMate: вкладка «Группы» → «Сканировать QR». В этом QR-коде содержится ключ шифрования группы, поэтому показывайте его только тем, кого хотите видеть в группе.", "Teman Anda bergabung dengan memindai ini di RunMate: tab Grup → 'Pindai QR'. QR ini berisi kunci enkripsi grup, jadi tunjukkan hanya kepada orang yang Anda inginkan di grup.")
        add("To record your route and speed, please allow 'Precise location'. Your location stays on your phone; groups only get coordinates relative to your start point.",
            "要记录跑步路线和速度，请允许“精确位置”权限。位置只保存在手机中，群组只会收到相对起点的坐标。", "走ったルートと速度を記録するには「正確な位置情報」を許可してください。位置は端末内にのみ保存され、グループにはスタート地点からの相対座標だけが共有されます。", "Para grabar tu ruta y velocidad, permite la 'Ubicación precisa'. Tu ubicación se queda en tu teléfono; los grupos solo reciben coordenadas relativas a tu punto de salida.", "Para gravar sua rota e velocidade, permita a 'Localização precisa'. Sua localização fica no seu celular; os grupos recebem apenas coordenadas relativas ao seu ponto de partida.", "Um Strecke und Tempo aufzuzeichnen, erlaube bitte 'Genauer Standort'. Dein Standort bleibt auf deinem Telefon; Gruppen erhalten nur Koordinaten relativ zu deinem Startpunkt.", "Pour enregistrer votre parcours et votre vitesse, autorisez la « Position précise ». Votre position reste sur votre téléphone ; les groupes ne reçoivent que des coordonnées relatives à votre point de départ.", "Чтобы записывать маршрут и скорость, разрешите «Точное местоположение». Ваше местоположение хранится только на телефоне; группы получают лишь координаты относительно точки старта.", "Untuk merekam rute dan kecepatan, izinkan 'Lokasi akurat'. Lokasi Anda tetap di ponsel; grup hanya menerima koordinat relatif terhadap titik awal.")
        add("Create a group and show the QR code to your friends.\nYou can have several groups and share runs with different people in each.",
            "创建群组并向朋友出示二维码。\n可以创建多个群组，在每个群组中与不同的人互相分享记录。", "グループを作ってQRコードを友だちに見せてください。\nグループは複数作成でき、グループごとに別の人たちと記録を共有できます。", "Crea un grupo y muestra el código QR a tus amigos.\nPuedes tener varios grupos y compartir carreras con personas distintas en cada uno.", "Crie um grupo e mostre o código QR aos seus amigos.\nVocê pode ter vários grupos e compartilhar corridas com pessoas diferentes em cada um.", "Erstelle eine Gruppe und zeige deinen Freunden den QR-Code.\nDu kannst mehrere Gruppen haben und in jeder mit anderen Personen Läufe teilen.", "Créez un groupe et montrez le QR code à vos amis.\nVous pouvez avoir plusieurs groupes et partager vos courses avec des personnes différentes dans chacun.", "Создайте группу и покажите QR-код друзьям.\nМожно создать несколько групп и в каждой обмениваться пробежками с разными людьми.", "Buat grup dan tunjukkan kode QR kepada teman Anda.\nAnda bisa punya beberapa grup dan berbagi lari dengan orang berbeda di tiap grup.")
        add("Received runs have no real location (only relative coordinates are shared), so they can't be shown on the map.",
            "收到的记录没有真实位置（只分享相对坐标），无法显示在地图上。", "受け取った記録には実際の位置がないため（相対座標のみ共有）、地図に表示できません。", "Las carreras recibidas no tienen ubicación real (solo se comparten coordenadas relativas), por lo que no pueden mostrarse en el mapa.", "As corridas recebidas não têm localização real (só coordenadas relativas são compartilhadas), por isso não podem aparecer no mapa.", "Empfangene Läufe haben keinen echten Standort (es werden nur relative Koordinaten geteilt) und können nicht auf der Karte gezeigt werden.", "Les courses reçues n'ont pas de position réelle (seules des coordonnées relatives sont partagées) et ne peuvent pas être affichées sur la carte.", "У полученных пробежек нет реальных координат (передаются только относительные), поэтому показать их на карте нельзя.", "Lari yang diterima tidak memiliki lokasi asli (hanya koordinat relatif yang dibagikan), sehingga tidak dapat ditampilkan di peta.")
        add("\n\nNote: {0} use(s) an old app version and can't receive the new key. Show them the invite QR again afterwards.",
            "\n\n注意：{0} 使用的是旧版应用，无法接收新密钥。移除后请重新向其出示邀请二维码。", "\n\n注意: {0}さんは旧バージョンのアプリのため新しいキーを受け取れません。外した後、招待QRをもう一度見せてください。", "\n\nNota: {0} usa(n) una versión antigua de la app y no puede(n) recibir la nueva clave. Vuelve a mostrarle(s) el QR de invitación después.", "\n\nNota: {0} usa(m) uma versão antiga do app e não pode(m) receber a nova chave. Mostre o QR de convite novamente depois.", "\n\nHinweis: {0} nutzt/nutzen eine alte App-Version und kann/können den neuen Schlüssel nicht erhalten. Zeige danach erneut den Einladungs-QR.", "\n\nRemarque : {0} utilise(nt) une ancienne version de l'appli et ne peu(ven)t pas recevoir la nouvelle clé. Montrez-leur à nouveau le QR d'invitation ensuite.", "\n\nВнимание: {0} — старая версия приложения, новый ключ получить нельзя. После удаления снова покажите QR-код приглашения.", "\n\nCatatan: {0} memakai versi aplikasi lama dan tidak dapat menerima kunci baru. Tunjukkan lagi QR undangan setelahnya.")
        add("Leave the group '{0}'? Runs you already received stay, but you'll no longer exchange runs with this group.",
            "要退出群组“{0}”吗？已收到的记录会保留，但不再与该群组互相分享记录。", "グループ「{0}」を退出しますか？受け取った記録は残りますが、このグループとは記録をやり取りしなくなります。", "¿Salir del grupo '{0}'? Las carreras ya recibidas se conservan, pero dejarás de intercambiar carreras con este grupo.", "Sair do grupo '{0}'? As corridas já recebidas permanecem, mas você deixará de trocar corridas com este grupo.", "Gruppe '{0}' verlassen? Bereits empfangene Läufe bleiben, aber du tauschst keine Läufe mehr mit dieser Gruppe aus.", "Quitter le groupe '{0}' ? Les courses déjà reçues sont conservées, mais vous n'échangerez plus de courses avec ce groupe.", "Выйти из группы «{0}»? Полученные пробежки останутся, но обмен пробежками с этой группой прекратится.", "Keluar dari grup '{0}'? Lari yang sudah diterima tetap ada, tetapi Anda tidak lagi bertukar lari dengan grup ini.")
        add("On the route view, drag with one finger to move and twist with two fingers to rotate. (rotation {0}°)",
            "在路线图上，单指拖动可移动，双指旋转可转动。（旋转 {0}°）", "ルート図の上で、1本指でドラッグすると移動、2本指で回すと回転します。（回転 {0}°）", "En la vista de ruta, arrastra con un dedo para mover y gira con dos dedos para rotar. (rotación {0}°)", "Na vista da rota, arraste com um dedo para mover e gire com dois dedos para rotacionar. (rotação {0}°)", "In der Streckenansicht mit einem Finger ziehen zum Verschieben, mit zwei Fingern drehen zum Rotieren. (Drehung {0}°)", "Sur la vue du parcours, faites glisser avec un doigt pour déplacer et tournez avec deux doigts pour pivoter. (rotation {0}°)", "На схеме маршрута перетаскивайте одним пальцем для сдвига и вращайте двумя пальцами для поворота. (поворот {0}°)", "Di tampilan rute, seret dengan satu jari untuk menggeser dan putar dengan dua jari untuk memutar. (rotasi {0}°)")
        add("Trimming can't be undone. Runs already sent to groups won't change on your group members' phones.",
            "裁剪后无法恢复。已发送到群组的记录在成员手机上不会改变。", "切り取った部分は元に戻せません。すでにグループに送った記録は、メンバー側では変わりません。", "El recorte no se puede deshacer. Las carreras ya enviadas a grupos no cambiarán en los teléfonos de los miembros.", "O corte não pode ser desfeito. As corridas já enviadas aos grupos não mudam nos celulares dos membros.", "Das Abschneiden kann nicht rückgängig gemacht werden. Bereits an Gruppen gesendete Läufe ändern sich auf den Telefonen der Mitglieder nicht.", "La coupe est irréversible. Les courses déjà envoyées aux groupes ne changeront pas sur les téléphones des membres.", "Обрезку нельзя отменить. Уже отправленные в группы пробежки на телефонах участников не изменятся.", "Pemotongan tidak dapat dibatalkan. Lari yang sudah dikirim ke grup tidak berubah di ponsel anggota.")
        add("All runs are overlaid with their start points placed at the real start of your first run.",
            "所有记录的起点都对齐到你第一条记录的实际出发位置后叠加绘制。", "すべての記録のスタート地点を、最初の自分の記録の実際の出発位置に合わせて重ねて描きます。", "Todas las carreras se superponen con su punto de salida colocado en la salida real de tu primera carrera.", "Todas as corridas são sobrepostas com o ponto de partida colocado na partida real da sua primeira corrida.", "Alle Läufe werden überlagert, wobei ihre Startpunkte am echten Start deines ersten Laufs liegen.", "Toutes les courses sont superposées, leur point de départ étant placé au départ réel de votre première course.", "Все пробежки наложены так, что их точки старта совмещены с реальным стартом вашей первой пробежки.", "Semua lari ditumpuk dengan titik awal ditempatkan di titik awal sebenarnya dari lari pertama Anda.")
        add("Tap or drag: value & location · Two fingers sideways: zoom range · up-down: adjust Y axis",
            "点按或拖动：数值和位置 · 双指横向：缩放区间 · 纵向：调整纵轴", "タップ・ドラッグ: 値と位置 · 2本指を横に: 区間を拡大 · 縦に: 縦軸を調整", "Toca o arrastra: valor y ubicación · Dos dedos en horizontal: zoom del tramo · en vertical: ajustar eje Y", "Toque ou arraste: valor e local · Dois dedos na horizontal: zoom do trecho · na vertical: ajustar eixo Y", "Tippen oder ziehen: Wert & Position · Zwei Finger quer: Bereich zoomen · hoch/runter: Y-Achse anpassen", "Toucher ou glisser : valeur et position · Deux doigts à l’horizontale : zoom de la plage · à la verticale : régler l’axe Y", "Касание или перетаскивание: значение и место · Два пальца по горизонтали: масштаб диапазона · по вертикали: ось Y", "Ketuk atau seret: nilai & lokasi · Dua jari mendatar: zoom rentang · tegak: atur sumbu Y")
        add("Delete this run from your phone? Runs already sent stay on your group members' phones.",
            "要从手机中删除这条记录吗？已发送给群组成员的记录会留在他们的手机上。", "この記録を端末から削除しますか？すでにグループメンバーに送った記録はメンバーの端末に残ります。", "¿Eliminar esta carrera de tu teléfono? Las carreras ya enviadas quedan en los teléfonos de los miembros del grupo.", "Excluir esta corrida do seu celular? As corridas já enviadas ficam nos celulares dos membros do grupo.", "Diesen Lauf von deinem Telefon löschen? Bereits gesendete Läufe bleiben auf den Telefonen der Gruppenmitglieder.", "Supprimer cette course de votre téléphone ? Les courses déjà envoyées restent sur les téléphones des membres du groupe.", "Удалить эту пробежку с телефона? Уже отправленные пробежки останутся на телефонах участников группы.", "Hapus lari ini dari ponsel Anda? Lari yang sudah dikirim tetap ada di ponsel anggota grup.")
        add("The selected runs don't have this data. (API elevation is fetched once you're online)",
            "所选记录没有此数据。（API 海拔会在联网后获取）", "選択した記録にこのデータがありません。（API標高はオンライン時に取得されます）", "Las carreras elegidas no tienen este dato. (La altitud de la API se obtiene al estar en línea)", "As corridas escolhidas não têm esse dado. (A altitude da API é obtida quando houver conexão)", "Die gewählten Läufe haben diese Daten nicht. (API-Höhe wird geladen, sobald du online bist)", "Les courses choisies n'ont pas cette donnée. (L'altitude API est récupérée une fois en ligne)", "У выбранных пробежек нет этих данных. (Высота из API загрузится при подключении к сети)", "Lari yang dipilih tidak memiliki data ini. (Elevasi API diambil saat online)")
        add("Totals include only the runs you sent to this group and the runs received through it.",
            "合计只包含你发送到该群组的记录以及通过该群组收到的记录。", "このグループに送った自分の記録と、このグループで受け取った記録だけを合算します。", "Los totales incluyen solo las carreras que enviaste a este grupo y las recibidas a través de él.", "Os totais incluem apenas as corridas que você enviou a este grupo e as recebidas por ele.", "Die Summen umfassen nur Läufe, die du an diese Gruppe gesendet hast, und die darüber empfangenen.", "Les totaux incluent uniquement les courses que vous avez envoyées à ce groupe et celles reçues par son biais.", "В итогах учитываются только пробежки, отправленные вами в эту группу и полученные через неё.", "Total hanya mencakup lari yang Anda kirim ke grup ini dan yang diterima melaluinya.")
        add("Only your own runs appear on the map. Group members' real locations are not shared.",
            "地图上只显示自己的记录。群组成员的真实位置不会被分享。", "地図には自分の記録だけが表示されます。グループメンバーの実際の位置は共有されません。", "En el mapa solo aparecen tus carreras. La ubicación real de los miembros del grupo no se comparte.", "No mapa aparecem apenas as suas corridas. A localização real dos membros do grupo não é compartilhada.", "Auf der Karte erscheinen nur deine eigenen Läufe. Echte Standorte der Gruppenmitglieder werden nicht geteilt.", "Seules vos courses apparaissent sur la carte. La position réelle des membres du groupe n'est pas partagée.", "На карте показаны только ваши пробежки. Реальное местоположение участников группы не передаётся.", "Hanya lari Anda yang muncul di peta. Lokasi asli anggota grup tidak dibagikan.")
        add("The key of group '{0}' has changed. Ask the group owner for a new invite QR.",
            "群组“{0}”的密钥已更换。请向群主重新获取邀请二维码。", "グループ「{0}」のキーが変更されました。オーナーに招待QRをもう一度もらってください。", "La clave del grupo '{0}' ha cambiado. Pide al propietario un nuevo QR de invitación.", "A chave do grupo '{0}' mudou. Peça ao dono do grupo um novo QR de convite.", "Der Schlüssel der Gruppe '{0}' wurde geändert. Bitte den Gruppeninhaber um einen neuen Einladungs-QR.", "La clé du groupe '{0}' a changé. Demandez un nouveau QR d'invitation au propriétaire.", "Ключ группы «{0}» изменён. Попросите у владельца новый QR-код приглашения.", "Kunci grup '{0}' telah berubah. Minta QR undangan baru dari pemilik grup.")
        add("Totals include all your runs and the runs your group members sent you.",
            "合计包含你的所有记录以及群组成员发送给你的记录。", "自分のすべての記録と、グループメンバーから届いた記録を合算します。", "Los totales incluyen todas tus carreras y las que te enviaron los miembros de tus grupos.", "Os totais incluem todas as suas corridas e as que os membros dos seus grupos enviaram a você.", "Die Summen umfassen alle deine Läufe und die Läufe, die dir Gruppenmitglieder gesendet haben.", "Les totaux incluent toutes vos courses et celles que les membres de vos groupes vous ont envoyées.", "В итогах учитываются все ваши пробежки и пробежки, присланные участниками групп.", "Total mencakup semua lari Anda dan lari yang dikirim anggota grup kepada Anda.")
        add("Pick your runs or your group members' runs to overlay and compare.",
            "选择自己或群组成员的记录，叠加对比。", "自分やグループメンバーの記録を選んで重ねて比較しましょう。", "Elige tus carreras o las de tu grupo para superponerlas y compararlas.", "Escolha suas corridas ou as do seu grupo para sobrepor e comparar.", "Wähle eigene Läufe oder die deiner Gruppenmitglieder zum Überlagern und Vergleichen.", "Choisissez vos courses ou celles de votre groupe pour les superposer et les comparer.", "Выберите свои пробежки или пробежки участников группы, чтобы наложить и сравнить.", "Pilih lari Anda atau anggota grup untuk ditumpuk dan dibandingkan.")
        add("Tap the chart to pick a point; everything after it can be removed.",
            "点按图表选择裁剪位置后，可以移除其后的部分。", "グラフをタップして切る地点を選ぶと、それ以降を切り取れます。", "Toca la gráfica para elegir un punto; se puede quitar todo lo posterior.", "Toque no gráfico para escolher um ponto; tudo o que vem depois pode ser removido.", "Tippe im Diagramm auf einen Punkt; alles danach kann entfernt werden.", "Touchez le graphique pour choisir un point ; tout ce qui suit pourra être supprimé.", "Нажмите на график, чтобы выбрать точку; всё после неё можно убрать.", "Ketuk grafik untuk memilih titik; semua setelahnya dapat dihapus.")
        add("Allow RunMate notifications in your phone settings to get alerts.",
            "需在手机设置中允许 RunMate 通知才会提醒。", "通知を受け取るには、端末の設定でRunMateの通知を許可してください。", "Permite las notificaciones de RunMate en los ajustes del teléfono para recibir avisos.", "Permita as notificações do RunMate nas configurações do celular para receber alertas.", "Erlaube RunMate-Benachrichtigungen in den Telefoneinstellungen, um Hinweise zu erhalten.", "Autorisez les notifications RunMate dans les réglages du téléphone pour recevoir des alertes.", "Разрешите уведомления RunMate в настройках телефона, чтобы получать оповещения.", "Izinkan notifikasi RunMate di setelan ponsel untuk menerima pemberitahuan.")
        add("Date,Name,Mine,Distance(km),Time,Avg pace(min/km),Ascent(m),File",
            "日期,姓名,我的记录,距离(km),时间,平均配速(分/km),爬升(m),文件", "日付,名前,自分の記録,距離(km),時間,平均ペース(分/km),上昇(m),ファイル", "Fecha,Nombre,Mía,Distancia(km),Tiempo,Ritmo medio(min/km),Ascenso(m),Archivo", "Data,Nome,Minha,Distância(km),Tempo,Ritmo médio(min/km),Subida(m),Arquivo", "Datum,Name,Eigener,Distanz(km),Zeit,Ø Pace(Min/km),Anstieg(m),Datei", "Date,Nom,Moi,Distance(km),Temps,Allure moy.(min/km),Dénivelé(m),Fichier", "Дата,Имя,Моя,Дистанция(км),Время,Средний темп(мин/км),Набор высоты(м),Файл", "Tanggal,Nama,Milik saya,Jarak(km),Waktu,Pace rata-rata(mnt/km),Tanjakan(m),Berkas")
        add("Your run is saved. Add friends on the Groups tab to share runs.",
            "记录已保存。在“群组”标签页添加朋友后即可发送记录。", "記録を保存しました。「グループ」タブで友だちを追加すると記録を送れます。", "Tu carrera está guardada. Añade amigos en la pestaña Grupos para compartir carreras.", "Sua corrida foi salva. Adicione amigos na aba Grupos para compartilhar corridas.", "Dein Lauf ist gespeichert. Füge im Tab Gruppen Freunde hinzu, um Läufe zu teilen.", "Votre course est enregistrée. Ajoutez des amis dans l'onglet Groupes pour partager vos courses.", "Пробежка сохранена. Добавьте друзей на вкладке «Группы», чтобы делиться пробежками.", "Lari Anda tersimpan. Tambahkan teman di tab Grup untuk berbagi lari.")
        add("Remove everything after the point picked on the chart ({0} km, {1})",
            "移除图表所选位置之后的部分（{0} km，{1}）", "グラフで選んだ地点より後を削除（{0} km、{1}）", "Quitar todo lo posterior al punto elegido en la gráfica ({0} km, {1})", "Remover tudo após o ponto escolhido no gráfico ({0} km, {1})", "Alles nach dem im Diagramm gewählten Punkt entfernen ({0} km, {1})", "Supprimer tout ce qui suit le point choisi sur le graphique ({0} km, {1})", "Убрать всё после точки, выбранной на графике ({0} км, {1})", "Hapus semua setelah titik yang dipilih di grafik ({0} km, {1})")
        add("Can't start recording. Please check the location permission.",
            "无法开始记录。请检查位置权限。", "記録を開始できません。位置情報の権限を確認してください。", "No se puede iniciar la grabación. Revisa el permiso de ubicación.", "Não foi possível iniciar a gravação. Verifique a permissão de localização.", "Aufzeichnung kann nicht gestartet werden. Bitte Standortberechtigung prüfen.", "Impossible de démarrer l'enregistrement. Vérifiez l'autorisation de localisation.", "Не удалось начать запись. Проверьте разрешение на геолокацию.", "Tidak dapat mulai merekam. Periksa izin lokasi.")
        add("Runs are overlaid with each start point at the origin (0,0).",
            "以各记录的起点为原点 (0,0) 叠加绘制。", "各記録のスタート地点を原点(0,0)に合わせて重ねて描きます。", "Las carreras se superponen con cada punto de salida en el origen (0,0).", "As corridas são sobrepostas com cada ponto de partida na origem (0,0).", "Die Läufe werden mit jedem Startpunkt im Ursprung (0,0) überlagert.", "Les courses sont superposées avec chaque point de départ à l'origine (0,0).", "Пробежки наложены так, что точка старта каждой находится в начале координат (0,0).", "Lari ditumpuk dengan setiap titik awal di titik asal (0,0).")
        add("Some runs were left out because they don't have this data.",
            "部分记录没有此数据，已排除。", "一部の記録はこのデータがないため除外されました。", "Algunas carreras se excluyeron porque no tienen este dato.", "Algumas corridas foram excluídas por não terem esse dado.", "Einige Läufe wurden ausgelassen, weil ihnen diese Daten fehlen.", "Certaines courses ont été exclues faute de cette donnée.", "Некоторые пробежки исключены: в них нет этих данных.", "Beberapa lari dikecualikan karena tidak memiliki data ini.")
        add("Couldn't connect. Please check your internet connection. {0}",
            "无法连接。请检查网络连接。{0}", "接続できませんでした。インターネット接続を確認してください。{0}", "No se pudo conectar. Revisa tu conexión a internet. {0}", "Não foi possível conectar. Verifique sua conexão com a internet. {0}", "Keine Verbindung. Bitte Internetverbindung prüfen. {0}", "Connexion impossible. Vérifiez votre connexion internet. {0}", "Не удалось подключиться. Проверьте интернет-соединение. {0}", "Tidak dapat terhubung. Periksa koneksi internet Anda. {0}")
        add("Couldn't reach the elevation API. Please try again later.",
            "无法连接海拔 API。请稍后重试。", "標高APIに接続できませんでした。しばらくしてからもう一度お試しください。", "No se pudo contactar con la API de altitud. Inténtalo más tarde.", "Não foi possível acessar a API de altitude. Tente novamente mais tarde.", "Die Höhen-API ist nicht erreichbar. Bitte später erneut versuchen.", "Impossible de joindre l'API d'altitude. Réessayez plus tard.", "Не удалось связаться с API высот. Повторите попытку позже.", "Tidak dapat menjangkau API elevasi. Coba lagi nanti.")
        add("Badge criteria are being prepared and will be added soon.",
            "徽章标准正在准备中，即将添加。", "バッジの基準は準備中です。近日追加されます。", "Los criterios de las insignias se están preparando y se añadirán pronto.", "Os critérios das medalhas estão sendo preparados e serão adicionados em breve.", "Die Kriterien für Abzeichen werden vorbereitet und bald ergänzt.", "Les critères des badges sont en préparation et seront ajoutés bientôt.", "Критерии значков готовятся и скоро появятся.", "Kriteria lencana sedang disiapkan dan akan segera ditambahkan.")
        add("Recording continues with the screen off or in other apps",
            "关闭屏幕或使用其他应用时仍会继续记录", "画面を消しても他のアプリを使っても記録は続きます", "La grabación continúa con la pantalla apagada o en otras apps", "A gravação continua com a tela desligada ou em outros apps", "Die Aufzeichnung läuft bei ausgeschaltetem Bildschirm oder in anderen Apps weiter", "L'enregistrement continue écran éteint ou dans d'autres applis", "Запись продолжается при выключенном экране и в других приложениях", "Perekaman berlanjut saat layar mati atau di aplikasi lain")
        add("Couldn't save the run. Please check your storage space.",
            "保存记录失败。请检查存储空间。", "記録を保存できませんでした。空き容量を確認してください。", "No se pudo guardar la carrera. Revisa el espacio de almacenamiento.", "Não foi possível salvar a corrida. Verifique o espaço de armazenamento.", "Der Lauf konnte nicht gespeichert werden. Bitte Speicherplatz prüfen.", "Impossible d'enregistrer la course. Vérifiez l'espace de stockage.", "Не удалось сохранить пробежку. Проверьте свободное место.", "Gagal menyimpan lari. Periksa ruang penyimpanan.")
        add("Turn on your phone's location, then tap Start again.",
            "请打开手机的位置功能后，再次点击“开始”。", "端末の位置情報をオンにしてから、もう一度スタートを押してください。", "Activa la ubicación del teléfono y vuelve a tocar Iniciar.", "Ative a localização do celular e toque em Iniciar novamente.", "Schalte den Standort deines Telefons ein und tippe erneut auf Start.", "Activez la localisation du téléphone, puis touchez à nouveau Démarrer.", "Включите геолокацию на телефоне и снова нажмите «Старт».", "Nyalakan lokasi ponsel, lalu ketuk Mulai lagi.")
        add("Earned when two or more of the above are outstanding",
            "以上徽章中有两项以上成绩非常出色时获得", "上のバッジのうち2つ以上で記録が非常に良いともらえるバッジ", "Se consigue cuando dos o más de las anteriores son sobresalientes", "Conquistada quando duas ou mais das anteriores são excelentes", "Wenn zwei oder mehr der obigen Bereiche herausragend sind", "Obtenu lorsque deux des catégories ci-dessus ou plus sont remarquables", "Выдаётся, когда два или более показателей выше — выдающиеся", "Diperoleh bila dua atau lebih kategori di atas sangat menonjol")
        add("Searching for GPS… move to where the sky is visible",
            "正在搜索 GPS 信号…请移动到能看到天空的地方", "GPS信号を探しています… 空が見える場所へ移動してください", "Buscando GPS… ve a un lugar con cielo despejado", "Procurando GPS… vá para um local com céu aberto", "GPS wird gesucht… geh an einen Ort mit freier Sicht zum Himmel", "Recherche du GPS… placez-vous à ciel ouvert", "Поиск GPS… выйдите на открытое место", "Mencari GPS… pindah ke tempat dengan langit terbuka")
        add("Phones set to an unsupported language show English.",
            "手机语言不受支持时显示英文。", "対応していない言語の端末では英語で表示されます。", "Los teléfonos con un idioma no compatible muestran inglés.", "Celulares em um idioma não compatível exibem inglês.", "Bei nicht unterstützter Telefonsprache wird Englisch angezeigt.", "Les téléphones dans une langue non prise en charge affichent l’anglais.", "Если язык телефона не поддерживается, используется английский.", "Ponsel dengan bahasa yang tidak didukung menampilkan bahasa Inggris.")
        add("Recovered a run that was interrupted unexpectedly.",
            "已恢复意外中断的跑步记录。", "予期せず中断されたランの記録を復元しました。", "Se recuperó una carrera que se interrumpió inesperadamente.", "Uma corrida interrompida inesperadamente foi recuperada.", "Ein unerwartet unterbrochener Lauf wurde wiederhergestellt.", "Une course interrompue de façon inattendue a été récupérée.", "Восстановлена пробежка, прерванная из-за сбоя.", "Lari yang terhenti tiba-tiba telah dipulihkan.")
        add("Earned by covering a long distance in a single run",
            "单次跑出较长距离即可获得", "1回で長い距離を走るともらえるバッジ", "Se consigue recorriendo una distancia larga en una sola carrera", "Conquistada ao percorrer uma longa distância em uma única corrida", "Für eine lange Distanz in einem einzelnen Lauf", "Obtenu en parcourant une longue distance en une seule course", "Выдаётся за большую дистанцию за одну пробежку", "Diperoleh dengan menempuh jarak jauh dalam satu lari")
        add("Can't open the camera. Please use 'Join by code'.",
            "无法打开相机。请使用“用邀请码加入”。", "カメラを開けません。「コードで参加」をご利用ください。", "No se puede abrir la cámara. Usa 'Unirse con código'.", "Não foi possível abrir a câmera. Use 'Entrar com código'.", "Kamera kann nicht geöffnet werden. Bitte 'Per Code beitreten' nutzen.", "Impossible d'ouvrir l'appareil photo. Utilisez « Rejoindre par code ».", "Не удаётся открыть камеру. Используйте «Вступить по коду».", "Tidak dapat membuka kamera. Gunakan 'Gabung dengan kode'.")
        add("No alerts; new runs show up when you open the app",
            "不提醒；打开应用时显示新记录", "通知なし。アプリを開くと新しい記録が表示されます", "Sin avisos; las carreras nuevas aparecen al abrir la app", "Sem alertas; as novas corridas aparecem ao abrir o app", "Keine Hinweise; neue Läufe erscheinen beim Öffnen der App", "Pas d'alertes ; les nouvelles courses s'affichent à l'ouverture de l'appli", "Без оповещений; новые пробежки видны при открытии приложения", "Tanpa pemberitahuan; lari baru muncul saat aplikasi dibuka")
    }

    private fun part1() {
        add("{0} waiting to send (sent automatically when online)",
            "{0} 条待发送（联网后自动发送）", "送信待ち {0}件（接続時に自動送信）", "{0} pendientes de envío (se envían solas al conectarse)", "{0} aguardando envio (enviadas automaticamente ao conectar)", "{0} warten auf Versand (werden online automatisch gesendet)", "{0} en attente d'envoi (envoi automatique une fois en ligne)", "Ожидают отправки: {0} (отправятся автоматически при подключении)", "{0} menunggu dikirim (otomatis terkirim saat online)")
        add("Tap or drag: value & location · Two fingers: zoom",
            "点按或拖动：查看数值和位置 · 双指：缩放", "タップ・ドラッグ: 値と位置 · 2本指: 拡大/縮小", "Toca o arrastra: valor y ubicación · Dos dedos: zoom", "Toque ou arraste: valor e local · Dois dedos: zoom", "Tippen oder ziehen: Wert & Position · Zwei Finger: Zoom", "Toucher ou glisser : valeur et position · Deux doigts : zoom", "Касание или перетаскивание: значение и место · Два пальца: масштаб", "Ketuk atau seret: nilai & lokasi · Dua jari: zoom")
        add("Earned by running for a long time in a single run",
            "单次跑步时间较长即可获得", "1回で長い時間走るともらえるバッジ", "Se consigue corriendo durante mucho tiempo en una sola carrera", "Conquistada ao correr por muito tempo em uma única corrida", "Für eine lange Laufzeit in einem einzelnen Lauf", "Obtenu en courant longtemps en une seule course", "Выдаётся за долгую пробежку", "Diperoleh dengan berlari lama dalam satu lari")
        add("Checks about every 15 minutes and notifies you",
            "约每 15 分钟检查一次并提醒", "約15分おきに確認してお知らせします", "Comprueba cada 15 minutos aprox. y te avisa", "Verifica a cada 15 minutos aprox. e avisa você", "Prüft etwa alle 15 Minuten und benachrichtigt dich", "Vérifie environ toutes les 15 minutes et vous prévient", "Проверка примерно раз в 15 минут с уведомлением", "Memeriksa sekitar tiap 15 menit dan memberi tahu Anda")
        add("Tapping Start will ask for location permission",
            "点击“开始”后会请求位置权限", "スタートを押すと位置情報の権限を求めます", "Al tocar Iniciar se pedirá el permiso de ubicación", "Ao tocar em Iniciar será pedida a permissão de localização", "Beim Tippen auf Start wird die Standortberechtigung abgefragt", "Toucher Démarrer demandera l'autorisation de localisation", "При нажатии «Старт» будет запрошено разрешение на геолокацию", "Mengetuk Mulai akan meminta izin lokasi")
        add("This run has already been sent to all groups.",
            "这条记录已发送到所有群组。", "この記録はすでにすべてのグループに送信済みです。", "Esta carrera ya se envió a todos los grupos.", "Esta corrida já foi enviada a todos os grupos.", "Dieser Lauf wurde bereits an alle Gruppen gesendet.", "Cette course a déjà été envoyée à tous les groupes.", "Эта пробежка уже отправлена во все группы.", "Lari ini sudah dikirim ke semua grup.")
        add("Where you tap Start becomes the origin (0, 0)",
            "点击“开始”的位置即为原点 (0, 0)", "スタートを押した場所が原点(0, 0)になります", "El lugar donde tocas Iniciar será el origen (0, 0)", "O local onde você toca em Iniciar será a origem (0, 0)", "Der Ort, an dem du Start tippst, wird zum Ursprung (0, 0)", "L'endroit où vous touchez Démarrer devient l'origine (0, 0)", "Место, где вы нажмёте «Старт», станет началом координат (0, 0)", "Tempat Anda mengetuk Mulai menjadi titik asal (0, 0)")
        add("The run was too short, so it wasn't saved.",
            "移动距离太短，未保存。", "移動距離が短すぎるため保存しませんでした。", "La carrera fue demasiado corta y no se guardó.", "A corrida foi curta demais e não foi salva.", "Der Lauf war zu kurz und wurde nicht gespeichert.", "La course était trop courte et n'a pas été enregistrée.", "Пробежка слишком короткая и не сохранена.", "Lari terlalu pendek sehingga tidak disimpan.")
        add("Tap: value & location · Two fingers: zoom",
            "点按：查看数值和位置 · 双指：缩放", "タップ: 値と位置 · 2本指: 拡大/縮小", "Toque: valor y ubicación · Dos dedos: zoom", "Toque: valor e local · Dois dedos: zoom", "Tippen: Wert & Position · Zwei Finger: Zoom", "Toucher : valeur et position · Deux doigts : zoom", "Касание: значение и место · Два пальца: масштаб", "Ketuk: nilai & lokasi · Dua jari: zoom")
        add("This name is shown to your group members.",
            "该名字会显示给群组成员。", "グループメンバーに表示される名前です。", "Este nombre se muestra a los miembros de tu grupo.", "Este nome é mostrado aos membros do seu grupo.", "Dieser Name wird deinen Gruppenmitgliedern angezeigt.", "Ce nom est visible par les membres de vos groupes.", "Это имя видят участники ваших групп.", "Nama ini ditampilkan kepada anggota grup Anda.")
        add("Precise location permission is required.",
            "需要精确位置权限。", "正確な位置情報の権限が必要です。", "Se requiere el permiso de ubicación precisa.", "É necessária a permissão de localização precisa.", "Die Berechtigung für den genauen Standort ist erforderlich.", "L'autorisation de localisation précise est requise.", "Требуется разрешение на точную геолокацию.", "Izin lokasi akurat diperlukan.")
        add("Earned by running at a fast average pace",
            "以较快的平均配速跑步即可获得", "速い平均ペースで走るともらえるバッジ", "Se consigue corriendo a un ritmo medio rápido", "Conquistada ao correr em um ritmo médio rápido", "Für Läufe mit schneller Durchschnittspace", "Obtenu en courant à une allure moyenne rapide", "Выдаётся за быстрый средний темп", "Diperoleh dengan berlari pada pace rata-rata yang cepat")
        add("No runs yet.\nTap Start on the Run tab!",
            "还没有记录。\n在“跑步”标签页点“开始”试试吧！", "まだ記録がありません。\n「ラン」タブでスタートを押してみましょう！", "Aún no hay carreras.\n¡Toca Iniciar en la pestaña Correr!", "Ainda não há corridas.\nToque em Iniciar na aba Correr!", "Noch keine Läufe.\nTippe im Tab Laufen auf Start!", "Aucune course pour le moment.\nTouchez Démarrer dans l'onglet Courir !", "Пробежек пока нет.\nНажмите «Старт» на вкладке «Бег»!", "Belum ada lari.\nKetuk Mulai di tab Lari!")
        add("This is not a RunMate invite QR/code.",
            "这不是 RunMate 的邀请二维码/邀请码。", "RunMateの招待QR/コードではありません。", "Esto no es un QR/código de invitación de RunMate.", "Isto não é um QR/código de convite do RunMate.", "Das ist kein RunMate-Einladungs-QR/-Code.", "Ce n'est pas un QR/code d'invitation RunMate.", "Это не QR-код и не код приглашения RunMate.", "Ini bukan QR/kode undangan RunMate.")
        add("Point at your friend's invite QR code",
            "请对准朋友的邀请二维码", "友だちの招待QRコードを映してください", "Apunta al código QR de invitación de tu amigo", "Aponte para o QR de convite do seu amigo", "Auf den Einladungs-QR-Code deines Freundes richten", "Visez le QR d'invitation de votre ami", "Наведите камеру на QR-код приглашения друга", "Arahkan ke kode QR undangan teman Anda")
        add("Align a run (pick one to move/rotate)",
            "对齐位置（选择一条记录进行移动/旋转）", "位置合わせ（記録を選んで移動・回転）", "Alinear una carrera (elige una para mover/rotar)", "Alinhar uma corrida (escolha uma para mover/girar)", "Lauf ausrichten (einen wählen zum Verschieben/Drehen)", "Aligner une course (choisissez-en une à déplacer/pivoter)", "Совместить пробежку (выберите, чтобы сдвинуть/повернуть)", "Sejajarkan lari (pilih satu untuk digeser/diputar)")
        add("Purchases aren't available right now.",
            "目前无法进行支付。", "現在は購入手続きを行えません。", "Las compras no están disponibles en este momento.", "As compras não estão disponíveis no momento.", "Käufe sind derzeit nicht möglich.", "Les achats ne sont pas disponibles pour le moment.", "Покупки сейчас недоступны.", "Pembelian tidak tersedia saat ini.")
        add("Only the latest 30 groups are shown.",
            "仅显示最近 30 组。", "直近30件のまとまりのみ表示します。", "Solo se muestran los últimos 30 grupos.", "São mostrados apenas os 30 grupos mais recentes.", "Es werden nur die letzten 30 Gruppen angezeigt.", "Seuls les 30 derniers regroupements sont affichés.", "Показаны только последние 30 интервалов.", "Hanya 30 kelompok terbaru yang ditampilkan.")
        add("GPS is off. Please turn on location.",
            "GPS 已关闭。请打开位置功能。", "GPSがオフです。位置情報をオンにしてください。", "El GPS está desactivado. Activa la ubicación.", "O GPS está desativado. Ative a localização.", "GPS ist aus. Bitte Standort einschalten.", "Le GPS est désactivé. Activez la localisation.", "GPS выключен. Включите геолокацию.", "GPS mati. Nyalakan lokasi.")
        add("Send this run to your group members?",
            "要把这条记录发送给群组成员吗？", "この記録をグループメンバーに送りますか？", "¿Enviar esta carrera a los miembros de tu grupo?", "Enviar esta corrida aos membros do seu grupo?", "Diesen Lauf an deine Gruppenmitglieder senden?", "Envoyer cette course aux membres de vos groupes ?", "Отправить эту пробежку участникам групп?", "Kirim lari ini ke anggota grup Anda?")
        add("You were removed from the group '{0}'.",
            "你已被移出群组“{0}”。", "グループ「{0}」から外されました。", "Te han eliminado del grupo '{0}'.", "Você foi removido do grupo '{0}'.", "Du wurdest aus der Gruppe '{0}' entfernt.", "Vous avez été retiré du groupe '{0}'.", "Вас удалили из группы «{0}».", "Anda dikeluarkan dari grup '{0}'.")
        add("Location (overlaid at start points)",
            "位置（对齐起点叠加显示）", "位置（スタート地点を合わせて重ね表示）", "Ubicación (superpuestas en el punto de salida)", "Localização (sobrepostas no ponto de partida)", "Position (an den Startpunkten überlagert)", "Position (superposées au point de départ)", "Положение (наложение по точкам старта)", "Lokasi (ditumpuk di titik awal)")
        add("☕ Buy the developer a coffee · \$2",
            "☕ 请开发者喝杯咖啡 · \$2", "☕ 開発者にコーヒーをおごる · \$2", "☕ Invita a un café al desarrollador · \$2", "☕ Pague um café para o desenvolvedor · \$2", "☕ Spendiere dem Entwickler einen Kaffee · \$2", "☕ Offrir un café au développeur · \$2", "☕ Угостить разработчика кофе · \$2", "☕ Traktir kopi untuk pengembang · \$2")
        add("Can't open the file save screen.",
            "无法打开文件保存界面。", "ファイル保存画面を開けません。", "No se puede abrir la pantalla para guardar archivos.", "Não foi possível abrir a tela de salvar arquivo.", "Der Speichern-Dialog kann nicht geöffnet werden.", "Impossible d'ouvrir l'écran d'enregistrement de fichier.", "Не удаётся открыть экран сохранения файла.", "Tidak dapat membuka layar simpan berkas.")
        add("Couldn't stop. Please tap again.",
            "结束失败。请再点一次。", "終了できませんでした。もう一度押してください。", "No se pudo detener. Toca de nuevo.", "Não foi possível parar. Toque novamente.", "Stoppen fehlgeschlagen. Bitte erneut tippen.", "Impossible d'arrêter. Touchez à nouveau.", "Не удалось остановить. Нажмите ещё раз.", "Gagal berhenti. Ketuk lagi.")
        add("Route (relative to start point)",
            "路线（相对起点的坐标）", "ルート（スタート地点からの相対座標）", "Ruta (relativa al punto de salida)", "Rota (relativa ao ponto de partida)", "Strecke (relativ zum Startpunkt)", "Parcours (relatif au point de départ)", "Маршрут (относительно точки старта)", "Rute (relatif terhadap titik awal)")
        add("{0} new run(s) from your groups  ›",
            "收到 {0} 条群组成员的新记录  ›", "グループメンバーの新しい記録が{0}件届きました  ›", "{0} carrera(s) nueva(s) de tus grupos  ›", "{0} corrida(s) nova(s) dos seus grupos  ›", "{0} neue(r) Lauf/Läufe aus deinen Gruppen  ›", "{0} nouvelle(s) course(s) de vos groupes  ›", "Новых пробежек из ваших групп: {0}  ›", "{0} lari baru dari grup Anda  ›")
        add("☕ Buy the developer a coffee · {0}",
            "☕ 请开发者喝杯咖啡 · {0}", "☕ 開発者にコーヒーをおごる · {0}", "☕ Invita a un café al desarrollador · {0}", "☕ Pague um café para o desenvolvedor · {0}", "☕ Spendiere dem Entwickler einen Kaffee · {0}", "☕ Offrir un café au développeur · {0}", "☕ Угостить разработчика кофе · {0}", "☕ Traktir kopi untuk pengembang · {0}")
        add("You're already in this group: {0}",
            "你已在该群组中：{0}", "すでに参加しているグループです: {0}", "Ya estás en este grupo: {0}", "Você já está neste grupo: {0}", "Du bist bereits in dieser Gruppe: {0}", "Vous êtes déjà dans ce groupe : {0}", "Вы уже в этой группе: {0}", "Anda sudah ada di grup ini: {0}")
        add("The purchase wasn't completed.",
            "支付未完成。", "購入は完了しませんでした。", "La compra no se completó.", "A compra não foi concluída.", "Der Kauf wurde nicht abgeschlossen.", "L'achat n'a pas abouti.", "Покупка не завершена.", "Pembelian tidak selesai.")
        add("Tap a bar to see exact values",
            "点按柱形可查看精确数值", "棒をタップすると正確な値を表示します", "Toca una barra para ver los valores exactos", "Toque em uma barra para ver os valores exatos", "Tippe auf einen Balken, um genaue Werte zu sehen", "Touchez une barre pour voir les valeurs exactes", "Нажмите на столбец, чтобы увидеть точные значения", "Ketuk batang untuk melihat nilai persisnya")
        add("Remove '{0}' from the group '{1}'?",
            "要将“{0}”移出群组“{1}”吗？", "「{0}」さんをグループ「{1}」から外しますか？", "¿Eliminar a '{0}' del grupo '{1}'?", "Remover '{0}' do grupo '{1}'?", "'{0}' aus der Gruppe '{1}' entfernen?", "Retirer '{0}' du groupe '{1}' ?", "Удалить «{0}» из группы «{1}»?", "Keluarkan '{0}' dari grup '{1}'?")
        add("Can't open the payment page.",
            "无法打开支付页面。", "支払いページを開けません。", "No se puede abrir la página de pago.", "Não foi possível abrir a página de pagamento.", "Die Zahlungsseite kann nicht geöffnet werden.", "Impossible d'ouvrir la page de paiement.", "Не удаётся открыть страницу оплаты.", "Tidak dapat membuka halaman pembayaran.")
        add("Remove the idle tail (last {0})",
            "移除末尾停留的部分（最后 {0}）", "止まっていた末尾を削除（最後の{0}）", "Quitar el final sin movimiento (últimos {0})", "Remover o final parado (últimos {0})", "Stillstand am Ende entfernen (letzte {0})", "Supprimer la fin à l’arrêt (dernières {0})", "Убрать простой в конце (последние {0})", "Hapus bagian akhir yang diam ({0} terakhir)")
        add("Couldn't remove the member.",
            "移除失败。", "外せませんでした。", "No se pudo eliminar al miembro.", "Não foi possível remover o membro.", "Mitglied konnte nicht entfernt werden.", "Impossible de retirer le membre.", "Не удалось удалить участника.", "Gagal mengeluarkan anggota.")
        add("Thank you for the coffee! ☕",
            "谢谢你的咖啡！☕", "コーヒーをありがとうございます！☕", "¡Gracias por el café! ☕", "Obrigado pelo café! ☕", "Danke für den Kaffee! ☕", "Merci pour le café ! ☕", "Спасибо за кофе! ☕", "Terima kasih atas kopinya! ☕")
        add("Earned by running regularly",
            "经常坚持跑步即可获得", "コツコツと頻繁に走るともらえるバッジ", "Se consigue corriendo con regularidad", "Conquistada ao correr com regularidade", "Für regelmäßiges Laufen", "Obtenu en courant régulièrement", "Выдаётся за регулярные пробежки", "Diperoleh dengan berlari secara rutin")
        add("{0} km · Open the app to stop",
            "{0} km · 打开应用以结束", "{0} km · 終了するにはアプリを開いてください", "{0} km · Abre la app para detener", "{0} km · Abra o app para parar", "{0} km · Zum Beenden App öffnen", "{0} km · Ouvrez l'appli pour arrêter", "{0} км · Откройте приложение, чтобы остановить", "{0} km · Buka aplikasi untuk berhenti")
        add("Location permission needed",
            "需要位置权限", "位置情報の権限が必要です", "Se necesita permiso de ubicación", "Permissão de localização necessária", "Standortberechtigung erforderlich", "Autorisation de localisation requise", "Нужно разрешение на геолокацию", "Izin lokasi diperlukan")
        add("Waiting for first location",
            "正在等待首次定位", "最初の位置を待っています", "Esperando la primera ubicación", "Aguardando a primeira localização", "Warte auf ersten Standort", "En attente de la première position", "Ожидание первой точки", "Menunggu lokasi pertama")
    }

    private fun part2() {
        add("New runs from your groups",
            "群组成员的新记录", "グループメンバーの新しい記録", "Nuevas carreras de tus grupos", "Novas corridas dos seus grupos", "Neue Läufe aus deinen Gruppen", "Nouvelles courses de vos groupes", "Новые пробежки из ваших групп", "Lari baru dari grup Anda")
        add("People (tap to hide/show)",
            "成员（点击隐藏/显示）", "人（タップで表示/非表示）", "Personas (toca para ocultar/mostrar)", "Pessoas (toque para ocultar/mostrar)", "Personen (tippen zum Ein-/Ausblenden)", "Personnes (touchez pour masquer/afficher)", "Люди (нажмите, чтобы скрыть/показать)", "Orang (ketuk untuk sembunyikan/tampilkan)")
        add("Charts (swipe left/right)",
            "图表（左右滑动切换）", "グラフ（左右にスワイプ）", "Gráficas (desliza a izquierda/derecha)", "Gráficos (deslize para os lados)", "Diagramme (links/rechts wischen)", "Graphiques (balayez gauche/droite)", "Графики (листайте влево/вправо)", "Grafik (geser kiri/kanan)")
        add("{0} runs · {1} km · {2} · ascent {3}m",
            "{0} 次 · {1} km · {2} · 爬升 {3}m", "{0}回 · {1} km · {2} · 上昇 {3}m", "{0} carreras · {1} km · {2} · ascenso {3}m", "{0} corridas · {1} km · {2} · subida {3}m", "{0} Läufe · {1} km · {2} · Anstieg {3}m", "{0} courses · {1} km · {2} · dénivelé {3}m", "Пробежек: {0} · {1} км · {2} · набор {3} м", "{0} kali · {1} km · {2} · tanjakan {3}m")
        add("Runs to compare (up to {0})",
            "要对比的记录（最多 {0} 条）", "比較する記録（最大{0}件）", "Carreras a comparar (máx. {0})", "Corridas para comparar (máx. {0})", "Läufe zum Vergleichen (max. {0})", "Courses à comparer ({0} max.)", "Пробежки для сравнения (до {0})", "Lari untuk dibandingkan (maks. {0})")
        add("No runs in this period.",
            "此时间范围内没有记录。", "この期間には記録がありません。", "No hay carreras en este periodo.", "Não há corridas neste período.", "Keine Läufe in diesem Zeitraum.", "Aucune course sur cette période.", "За этот период пробежек нет.", "Tidak ada lari pada periode ini.")
        add("Select runs to compare",
            "选择要对比的记录", "比較する記録を選択", "Elige carreras para comparar", "Escolha corridas para comparar", "Läufe zum Vergleichen wählen", "Choisir les courses à comparer", "Выберите пробежки для сравнения", "Pilih lari untuk dibandingkan")
        add("Get elevation from API",
            "从海拔 API 获取高度", "標高APIから高度を取得", "Obtener altitud de la API", "Obter altitude da API", "Höhe von API laden", "Récupérer l'altitude via l'API", "Получить высоту из API", "Ambil elevasi dari API")
        add("Can't start recording.",
            "无法开始记录。", "記録を開始できません。", "No se puede iniciar la grabación.", "Não foi possível iniciar a gravação.", "Aufzeichnung kann nicht gestartet werden.", "Impossible de démarrer l'enregistrement.", "Не удалось начать запись.", "Tidak dapat mulai merekam.")
        add("Open location settings",
            "打开位置设置", "位置情報の設定を開く", "Abrir ajustes de ubicación", "Abrir ajustes de localização", "Standorteinstellungen öffnen", "Ouvrir les réglages de localisation", "Открыть настройки геолокации", "Buka setelan lokasi")
        add("Finish without sending",
            "不发送并结束", "送らずに終了", "Terminar sin enviar", "Terminar sem enviar", "Ohne Senden beenden", "Terminer sans envoyer", "Завершить без отправки", "Selesai tanpa mengirim")
        add("Smoothing: {0} s average",
            "平滑：{0} 秒平均", "なめらかさ: {0}秒平均", "Suavizado: media de {0} s", "Suavização: média de {0} s", "Glättung: {0}-s-Mittel", "Lissage : moyenne sur {0} s", "Сглаживание: среднее за {0} с", "Penghalusan: rata-rata {0} dtk")
        add("Location (GPS) is off",
            "位置（GPS）已关闭", "位置情報（GPS）がオフです", "La ubicación (GPS) está desactivada", "A localização (GPS) está desativada", "Standort (GPS) ist aus", "La localisation (GPS) est désactivée", "Геолокация (GPS) выключена", "Lokasi (GPS) mati")
        add("No badges earned yet.",
            "还没有获得徽章。", "まだ獲得したバッジがありません。", "Aún no has conseguido insignias.", "Nenhuma medalha conquistada ainda.", "Noch keine Abzeichen erhalten.", "Aucun badge obtenu pour le moment.", "Значков пока нет.", "Belum ada lencana yang diperoleh.")
        add("Joined the group '{0}'.",
            "已加入群组“{0}”。", "グループ「{0}」に参加しました。", "Te uniste al grupo '{0}'.", "Você entrou no grupo '{0}'.", "Der Gruppe '{0}' beigetreten.", "Vous avez rejoint le groupe '{0}'.", "Вы вступили в группу «{0}».", "Bergabung dengan grup '{0}'.")
        add("  · You're the owner",
            "  · 我是群主", "  · 自分がオーナー", "  · Eres el propietario", "  · Você é o dono", "  · Du bist Inhaber", "  · Vous êtes propriétaire", "  · Вы владелец", "  · Anda pemilik")
        add("No runs received yet",
            "还没有收到记录", "まだ受け取った記録がありません", "Aún no has recibido carreras", "Nenhuma corrida recebida ainda", "Noch keine Läufe empfangen", "Aucune course reçue pour le moment", "Полученных пробежек пока нет", "Belum ada lari yang diterima")
        add("Weak GPS signal (±{0}m)",
            "GPS 信号弱（±{0}m）", "GPS信号が弱い（±{0}m）", "Señal GPS débil (±{0}m)", "Sinal de GPS fraco (±{0}m)", "Schwaches GPS-Signal (±{0}m)", "Signal GPS faible (±{0}m)", "Слабый сигнал GPS (±{0} м)", "Sinyal GPS lemah (±{0}m)")
        add("Follow phone setting",
            "跟随手机设置", "端末の設定に従う", "Seguir ajuste del teléfono", "Seguir configuração do celular", "Telefoneinstellung folgen", "Suivre le réglage du téléphone", "Как в настройках телефона", "Ikuti setelan ponsel")
        add("The end was trimmed.",
            "已裁剪末尾。", "末尾を切り取りました。", "Se recortó el final.", "O final foi cortado.", "Das Ende wurde abgeschnitten.", "La fin a été coupée.", "Конец обрезан.", "Bagian akhir telah dipotong.")
        add("Can't trim this run.",
            "无法裁剪这条记录。", "この記録は切り取れません。", "No se puede recortar esta carrera.", "Não é possível cortar esta corrida.", "Dieser Lauf kann nicht gekürzt werden.", "Impossible de couper cette course.", "Эту пробежку нельзя обрезать.", "Lari ini tidak dapat dipotong.")
        add("GPS is unavailable.",
            "GPS 不可用。", "GPSを利用できません。", "El GPS no está disponible.", "O GPS não está disponível.", "GPS ist nicht verfügbar.", "Le GPS n'est pas disponible.", "GPS недоступен.", "GPS tidak tersedia.")
        add("Invite code copied.",
            "已复制邀请码。", "招待コードをコピーしました。", "Código de invitación copiado.", "Código de convite copiado.", "Einladungscode kopiert.", "Code d'invitation copié.", "Код приглашения скопирован.", "Kode undangan disalin.")
        add("Fetching elevation…",
            "正在获取海拔…", "標高を取得中…", "Obteniendo altitud…", "Obtendo altitude…", "Höhe wird geladen…", "Récupération de l'altitude…", "Загрузка высот…", "Mengambil elevasi…")
        add("Badges you can earn",
            "可以挑战的徽章", "挑戦できるバッジ", "Insignias que puedes conseguir", "Medalhas que você pode conquistar", "Abzeichen, die du verdienen kannst", "Badges à obtenir", "Значки, которые можно получить", "Lencana yang bisa diraih")
        add("Distance in one run",
            "单次跑步距离", "1回のランの距離", "Distancia en una carrera", "Distância em uma corrida", "Distanz in einem Lauf", "Distance en une course", "Дистанция за одну пробежку", "Jarak dalam satu lari")
        add("Getting GPS ready…",
            "GPS 准备中…", "GPS準備中…", "Preparando GPS…", "Preparando GPS…", "GPS wird vorbereitet…", "Préparation du GPS…", "Подготовка GPS…", "Menyiapkan GPS…")
        add("Recording · GPS ±{0}m",
            "记录中 · GPS ±{0}m", "記録中 · GPS ±{0}m", "Grabando · GPS ±{0}m", "Gravando · GPS ±{0}m", "Aufzeichnung · GPS ±{0}m", "Enregistrement · GPS ±{0}m", "Запись · GPS ±{0} м", "Merekam · GPS ±{0}m")
        add("Paste invite code",
            "粘贴邀请码", "招待コードを貼り付け", "Pega el código de invitación", "Cole o código de convite", "Einladungscode einfügen", "Collez le code d'invitation", "Вставьте код приглашения", "Tempel kode undangan")
        add("Sent to {0} group(s)",
            "已发送到 {0} 个群组", "{0}グループに送信済み", "Enviada a {0} grupo(s)", "Enviada a {0} grupo(s)", "An {0} Gruppe(n) gesendet", "Envoyée à {0} groupe(s)", "Отправлено в группы: {0}", "Terkirim ke {0} grup")
        add("Exported to file.",
            "已导出到文件。", "ファイルにエクスポートしました。", "Exportada a archivo.", "Exportada para arquivo.", "In Datei exportiert.", "Exportée dans un fichier.", "Экспортировано в файл.", "Diekspor ke berkas.")
        add("Open app settings",
            "打开应用设置", "アプリ設定を開く", "Abrir ajustes de la app", "Abrir ajustes do app", "App-Einstellungen öffnen", "Ouvrir les réglages de l'appli", "Открыть настройки приложения", "Buka setelan aplikasi")
        add("RunMate version {0}",
            "RunMate 版本 {0}", "RunMate バージョン {0}", "RunMate versión {0}", "RunMate versão {0}", "RunMate Version {0}", "RunMate version {0}", "RunMate, версия {0}", "RunMate versi {0}")
        add("Rel. elev (API)",
            "相对高度(API)", "相対高度(API)", "Altura rel. (API)", "Altura rel. (API)", "Rel. Höhe (API)", "Altitude rel. (API)", "Отн. высота (API)", "Tinggi rel. (API)")
        add("Rel. elev (GPS)",
            "相对高度(GPS)", "相対高度(GPS)", "Altura rel. (GPS)", "Altura rel. (GPS)", "Rel. Höhe (GPS)", "Altitude rel. (GPS)", "Отн. высота (GPS)", "Tinggi rel. (GPS)")
        add("Show full range",
            "显示全部区间", "全区間を表示", "Ver todo el tramo", "Ver trecho completo", "Gesamten Bereich zeigen", "Afficher toute la plage", "Показать весь диапазон", "Tampilkan seluruh rentang")
        add("Exported {0} runs.",
            "已导出 {0} 条记录。", "記録を{0}件エクスポートしました。", "Se exportaron {0} carreras.", "{0} corridas exportadas.", "{0} Läufe exportiert.", "{0} courses exportées.", "Экспортировано пробежек: {0}.", "{0} lari diekspor.")
        add("Waiting to send",
            "等待发送", "送信待ち", "Pendiente de envío", "Aguardando envio", "Wartet auf Versand", "En attente d'envoi", "Ожидает отправки", "Menunggu dikirim")
        add("Time in one run",
            "单次跑步时间", "1回のランの時間", "Tiempo en una carrera", "Tempo em uma corrida", "Zeit in einem Lauf", "Durée en une course", "Время одной пробежки", "Waktu dalam satu lari")
        add("New run alerts",
            "新记录提醒", "新しい記録の通知", "Avisos de carreras nuevas", "Alertas de novas corridas", "Hinweise auf neue Läufe", "Alertes de nouvelles courses", "Оповещения о новых пробежках", "Pemberitahuan lari baru")
    }

    private fun part3() {
        add("Not synced yet",
            "尚未同步", "まだ同期していません", "Aún sin sincronizar", "Ainda não sincronizado", "Noch nicht synchronisiert", "Pas encore synchronisé", "Синхронизации ещё не было", "Belum disinkronkan")
        add("New group name",
            "新群组名称", "新しいグループ名", "Nombre del nuevo grupo", "Nome do novo grupo", "Name der neuen Gruppe", "Nom du nouveau groupe", "Название новой группы", "Nama grup baru")
        add("Invite QR code",
            "邀请二维码", "招待QRコード", "Código QR de invitación", "Código QR de convite", "Einladungs-QR-Code", "QR code d'invitation", "QR-код приглашения", "Kode QR undangan")
        add("Export failed.",
            "导出失败。", "エクスポートに失敗しました。", "Error al exportar.", "Falha ao exportar.", "Export fehlgeschlagen.", "Échec de l'export.", "Не удалось экспортировать.", "Ekspor gagal.")
        add("Add to Analyze",
            "添加到分析", "分析に追加", "Añadir a Análisis", "Adicionar à Análise", "Zur Analyse hinzufügen", "Ajouter à l'analyse", "Добавить в анализ", "Tambah ke Analisis")
        add("Send to groups",
            "发送到群组", "グループに送信", "Enviar a grupos", "Enviar a grupos", "An Gruppen senden", "Envoyer aux groupes", "Отправить в группы", "Kirim ke grup")
        add("GPS ready (±{0}m)",
            "GPS 已就绪（±{0}m）", "GPS準備完了（±{0}m）", "GPS listo (±{0}m)", "GPS pronto (±{0}m)", "GPS bereit (±{0}m)", "GPS prêt (±{0}m)", "GPS готов (±{0} м)", "GPS siap (±{0}m)")
        add("Recording run",
            "正在记录跑步", "ランを記録中", "Grabando carrera", "Gravando corrida", "Lauf wird aufgezeichnet", "Enregistrement de la course", "Идёт запись пробежки", "Merekam lari")
        add("New run from {0}",
            "{0} 的新跑步记录", "{0}さんの新しいラン記録", "Nueva carrera de {0}", "Nova corrida de {0}", "Neuer Lauf von {0}", "Nouvelle course de {0}", "Новая пробежка: {0}", "Lari baru dari {0}")
        add("Cum. distance",
            "累计距离", "累積距離", "Distancia acum.", "Distância acum.", "Kum. Distanz", "Distance cumulée", "Накопл. дистанция", "Jarak kumulatif")
        add("Archive route",
            "存档通道", "保管経路", "Ruta de archivo", "Rota de arquivo", "Archiv-Weg", "Voie d'archive", "Архивный канал", "Jalur arsip")
        add("Last sync {0} · {1}",
            "上次同步 {0} · {1}", "最終同期 {0} · {1}", "Última sincronización {0} · {1}", "Última sincronização {0} · {1}", "Letzte Synchronisierung {0} · {1}", "Dernière synchro {0} · {1}", "Последняя синхронизация {0} · {1}", "Sinkron terakhir {0} · {1}")
        add("Members of '{0}'",
            "“{0}”成员管理", "「{0}」のメンバー管理", "Miembros de '{0}'", "Membros de '{0}'", "Mitglieder von '{0}'", "Membres de '{0}'", "Участники «{0}»", "Anggota '{0}'")
        add("Remove member",
            "移除成员", "メンバーを外す", "Eliminar miembro", "Remover membro", "Mitglied entfernen", "Retirer un membre", "Удалить участника", "Keluarkan anggota")
        add("Mark all read",
            "全部已读", "すべて既読", "Marcar todo leído", "Marcar tudo como lido", "Alle gelesen", "Tout marquer lu", "Прочитать все", "Tandai semua dibaca")
        add("Members' runs",
            "成员记录", "メンバーの記録", "Carreras de miembros", "Corridas dos membros", "Läufe der Mitglieder", "Courses des membres", "Пробежки участников", "Lari anggota")
        add("Run complete!",
            "跑步完成！", "ラン完了！", "¡Carrera completada!", "Corrida concluída!", "Lauf beendet!", "Course terminée !", "Пробежка завершена!", "Lari selesai!")
        add("Select period",
            "选择时间范围", "期間を選択", "Elegir periodo", "Escolher período", "Zeitraum wählen", "Choisir la période", "Выберите период", "Pilih periode")
        add("Earned badges",
            "已获得的徽章", "獲得したバッジ", "Insignias conseguidas", "Medalhas conquistadas", "Erhaltene Abzeichen", "Badges obtenus", "Полученные значки", "Lencana yang diperoleh")
        add("Compare runs",
            "对比记录", "ラン比較", "Comparar carreras", "Comparar corridas", "Läufe vergleichen", "Comparer", "Сравнение", "Bandingkan lari")
        add("No runs yet.",
            "还没有记录。", "まだ記録がありません。", "Aún no hay carreras.", "Ainda não há corridas.", "Noch keine Läufe.", "Aucune course pour le moment.", "Пробежек пока нет.", "Belum ada lari.")
        add("Create group",
            "创建群组", "グループ作成", "Crear grupo", "Criar grupo", "Gruppe erstellen", "Créer un groupe", "Создать группу", "Buat grup")
        add("Join by code",
            "用邀请码加入", "コードで参加", "Unirse con código", "Entrar com código", "Per Code beitreten", "Rejoindre par code", "Вступить по коду", "Gabung dengan kode")
        add("{0} members: Me",
            "{0} 人：我", "{0}人: 自分", "{0} miembros: Yo", "{0} membros: Eu", "{0} Mitglieder: Ich", "{0} membres : Moi", "Участников: {0}: Я", "{0} anggota: Saya")
        add("Invite to '{0}'",
            "邀请加入“{0}”", "「{0}」への招待", "Invitación a '{0}'", "Convite para '{0}'", "Einladung zu '{0}'", "Invitation à '{0}'", "Приглашение в «{0}»", "Undangan ke '{0}'")
        add("API source: {0}",
            "API 来源：{0}", "APIの出典: {0}", "Fuente de la API: {0}", "Fonte da API: {0}", "API-Quelle: {0}", "Source API : {0}", "Источник API: {0}", "Sumber API: {0}")
        add("Current pace",
            "当前配速", "現在のペース", "Ritmo actual", "Ritmo atual", "Akt. Pace", "Allure actuelle", "Текущий темп", "Pace saat ini")
        add("Last 30 days",
            "最近 30 天", "直近30日", "Últimos 30 días", "Últimos 30 dias", "Letzte 30 Tage", "30 derniers jours", "Последние 30 дней", "30 hari terakhir")
        add("Achievements",
            "成就", "実績", "Logros", "Conquistas", "Erfolge", "Succès", "Достижения", "Pencapaian")
        add("Trim the end",
            "裁剪末尾", "末尾を切り取る", "Recortar el final", "Cortar o final", "Ende abschneiden", "Couper la fin", "Обрезать конец", "Potong bagian akhir")
        add("Select runs",
            "选择记录", "記録を選択", "Elegir carreras", "Escolher corridas", "Läufe wählen", "Choisir des courses", "Выбрать пробежки", "Pilih lari")
        add("Removed '{0}'.",
            "已移除“{0}”。", "「{0}」さんを外しました。", "Se eliminó a '{0}'.", "'{0}' foi removido.", "'{0}' wurde entfernt.", "'{0}' a été retiré.", "«{0}» удалён.", "'{0}' telah dikeluarkan.")
        add("Leave group",
            "退出群组", "グループを退出", "Salir del grupo", "Sair do grupo", "Gruppe verlassen", "Quitter le groupe", "Выйти из группы", "Keluar dari grup")
        add("Distance km",
            "距离 km", "距離 km", "Distancia km", "Distância km", "Distanz km", "Distance km", "Дистанция, км", "Jarak km")
        add("Consistency",
            "坚持", "継続", "Constancia", "Constância", "Beständigkeit", "Régularité", "Регулярность", "Konsistensi")
        add("Coming soon",
            "准备中", "準備中", "Próximamente", "Em breve", "Demnächst", "Bientôt", "Скоро", "Segera hadir")
        add("Fast route",
            "快速通道", "高速経路", "Ruta rápida", "Rota rápida", "Schneller Weg", "Voie rapide", "Быстрый канал", "Jalur cepat")
        add("Export all",
            "全部导出", "すべてエクスポート", "Exportar todo", "Exportar tudo", "Alle exportieren", "Tout exporter", "Экспорт всех", "Ekspor semua")
        add("Elev (API)",
            "高度(API)", "高度(API)", "Altura (API)", "Altura (API)", "Höhe (API)", "Altitude (API)", "Высота (API)", "Tinggi (API)")
        add("Elev (GPS)",
            "高度(GPS)", "高度(GPS)", "Altura (GPS)", "Altura (GPS)", "Höhe (GPS)", "Altitude (GPS)", "Высота (GPS)", "Tinggi (GPS)")
    }

    private fun part4() {
        add("Export CSV",
            "导出 CSV", "CSVでエクスポート", "Exportar CSV", "Exportar CSV", "CSV exportieren", "Exporter en CSV", "Экспорт CSV", "Ekspor CSV")
        add("Export GPX",
            "导出 GPX", "GPXでエクスポート", "Exportar GPX", "Exportar GPX", "GPX exportieren", "Exporter en GPX", "Экспорт GPX", "Ekspor GPX")
        add("Delete run",
            "删除记录", "記録を削除", "Eliminar carrera", "Excluir corrida", "Lauf löschen", "Supprimer la course", "Удалить пробежку", "Hapus lari")
        add("GPS elev m",
            "GPS 高度 m", "GPS高度 m", "Altura GPS m", "Altura GPS m", "GPS-Höhe m", "Altitude GPS m", "Высота GPS, м", "Tinggi GPS m")
        add("This month",
            "本月", "今月", "Este mes", "Este mês", "Dieser Monat", "Ce mois", "Этот месяц", "Bulan ini")
        add("{0} new runs",
            "{0} 条新跑步记录", "新しいラン記録 {0}件", "{0} carreras nuevas", "{0} corridas novas", "{0} neue Läufe", "{0} nouvelles courses", "Новых пробежек: {0}", "{0} lari baru")
        add("Invite QR",
            "邀请二维码", "招待QR", "QR de invitación", "QR de convite", "Einladungs-QR", "QR d'invitation", "QR приглашения", "QR undangan")
        add("Copy code",
            "复制邀请码", "コードをコピー", "Copiar código", "Copiar código", "Code kopieren", "Copier le code", "Скопировать код", "Salin kode")
        add("This week",
            "本周", "今週", "Esta semana", "Esta semana", "Diese Woche", "Cette semaine", "Эта неделя", "Minggu ini")
        add("This year",
            "今年", "今年", "Este año", "Este ano", "Dieses Jahr", "Cette année", "Этот год", "Tahun ini")
        add("Settings",
            "设置", "設定", "Ajustes", "Ajustes", "Optionen", "Réglages", "Настройки", "Setelan")
        add("Distance",
            "距离", "距離", "Distancia", "Distância", "Distanz", "Distance", "Дистанция", "Jarak")
        add("Avg pace",
            "平均配速", "平均ペース", "Ritmo medio", "Ritmo médio", "Ø Pace", "Allure moy.", "Средний темп", "Pace rata-rata")
        add("Loading…",
            "加载中…", "読み込み中…", "Cargando…", "Carregando…", "Wird geladen…", "Chargement…", "Загрузка…", "Memuat…")
        add("Location",
            "位置", "位置", "Ubicación", "Localização", "Position", "Position", "Положение", "Lokasi")
        add("Group by",
            "分组", "まとめ方", "Agrupar por", "Agrupar por", "Gruppieren nach", "Regrouper par", "Группировка", "Kelompokkan")
        add("3 months",
            "3 个月", "3か月", "3 meses", "3 meses", "3 Monate", "3 mois", "3 месяца", "3 bulan")
        add("Syncing…",
            "同步中…", "同期中…", "Sincronizando…", "Sincronizando…", "Synchronisiere…", "Synchronisation…", "Синхронизация…", "Menyinkronkan…")
        add("Sync now",
            "立即同步", "今すぐ同期", "Sincronizar ahora", "Sincronizar agora", "Jetzt synchronisieren", "Synchroniser", "Синхронизировать", "Sinkronkan sekarang")
        add("Not sent",
            "未发送", "未送信", "No enviada", "Não enviada", "Nicht gesendet", "Non envoyée", "Не отправлено", "Belum dikirim")
        add("Ascent m",
            "爬升 m", "上昇 m", "Ascenso m", "Subida m", "Anstieg m", "Dénivelé m", "Набор, м", "Tanjakan m")
        add("Language",
            "语言", "言語", "Idioma", "Idioma", "Sprache", "Langue", "Язык", "Bahasa")
        add("Champion",
            "冠军", "チャンピオン", "Campeón", "Campeão", "Champion", "Champion", "Чемпион", "Juara")
        add("History",
            "记录", "履歴", "Historial", "Histórico", "Verlauf", "Historique", "История", "Riwayat")
        add("Analyze",
            "分析", "分析", "Análisis", "Análise", "Analyse", "Analyse", "Анализ", "Analisis")
        add("Per run",
            "按次", "ランごと", "Por carrera", "Por corrida", "Pro Lauf", "Par course", "По пробежкам", "Per lari")
        add("Monthly",
            "按月", "月別", "Mensual", "Mensal", "Monatlich", "Par mois", "По месяцам", "Bulanan")
        add("No name",
            "未命名", "名前なし", "Sin nombre", "Sem nome", "Ohne Namen", "Sans nom", "Без имени", "Tanpa nama")
        add("4 weeks",
            "4 周", "4週間", "4 semanas", "4 semanas", "4 Wochen", "4 semaines", "4 недели", "4 minggu")
        add("My name",
            "我的名字", "自分の名前", "Mi nombre", "Meu nome", "Mein Name", "Mon nom", "Моё имя", "Nama saya")
        add("Scan QR",
            "扫描二维码", "QRスキャン", "Escanear QR", "Ler QR", "QR scannen", "Scanner le QR", "Сканировать QR", "Pindai QR")
        add("Members",
            "成员管理", "メンバー管理", "Miembros", "Membros", "Mitglieder", "Membres", "Участники", "Anggota")
        add("My runs",
            "我的记录", "自分の記録", "Mis carreras", "Minhas corridas", "Meine Läufe", "Mes courses", "Мои пробежки", "Lari saya")
        add("Rank by",
            "排名依据", "基準", "Ordenar por", "Ordenar por", "Sortieren nach", "Classer par", "Рейтинг по", "Urutkan menurut")
        add("Groups",
            "群组", "グループ", "Grupos", "Grupos", "Gruppen", "Groupes", "Группы", "Grup")
        add("min/km",
            "分/km", "分/km", "min/km", "min/km", "Min/km", "min/km", "мин/км", "mnt/km")
        add("Weekly",
            "按周", "週別", "Semanal", "Semanal", "Wöchentlich", "Par semaine", "По неделям", "Mingguan")
        add("Ascent",
            "爬升", "上昇", "Ascenso", "Subida", "Anstieg", "Dénivelé", "Набор высоты", "Tanjakan")
        add("Runner",
            "跑者", "ランナー", "Corredor", "Corredor", "Läufer", "Coureur", "Бегун", "Pelari")
        add("Trends",
            "趋势", "推移", "Tendencias", "Tendências", "Trends", "Tendances", "Динамика", "Tren")
    }

    private fun part5() {
        add("Remove",
            "移除", "外す", "Quitar", "Remover", "Entfernen", "Retirer", "Убрать", "Hapus")
        add("Y axis",
            "纵轴", "縦軸", "Eje Y", "Eixo Y", "Y-Achse", "Axe Y", "Ось Y", "Sumbu Y")
        add("X axis",
            "横轴", "横軸", "Eje X", "Eixo X", "X-Achse", "Axe X", "Ось X", "Sumbu X")
        add("Metric",
            "项目", "項目", "Métrica", "Métrica", "Messwert", "Mesure", "Показатель", "Metrik")
        add("Period",
            "时间范围", "期間", "Periodo", "Período", "Zeitraum", "Période", "Период", "Periode")
        add("1 year",
            "1 年", "1年", "1 año", "1 ano", "1 Jahr", "1 an", "1 год", "1 tahun")
        add("Change",
            "修改", "変更", "Cambiar", "Alterar", "Ändern", "Modifier", "Изменить", "Ubah")
        add("Alerts",
            "提醒", "通知", "Avisos", "Alertas", "Hinweise", "Alertes", "Оповещения", "Notifikasi")
        add("Cancel",
            "取消", "キャンセル", "Cancelar", "Cancelar", "Abbrechen", "Annuler", "Отмена", "Batal")
        add("‹ Back",
            "‹ 返回", "‹ 戻る", "‹ Atrás", "‹ Voltar", "‹ Zurück", "‹ Retour", "‹ Назад", "‹ Kembali")
        add("My run",
            "我的记录", "自分の記録", "Mi carrera", "Minha corrida", "Mein Lauf", "Ma course", "Моя пробежка", "Lari saya")
        add("{0}'s run",
            "{0} 的记录", "{0}さんの記録", "Carrera de {0}", "Corrida de {0}", "Lauf von {0}", "Course de {0}", "Пробежка: {0}", "Lari {0}")
        add("Delete",
            "删除", "削除", "Eliminar", "Excluir", "Löschen", "Supprimer", "Удалить", "Hapus")
        add("Custom",
            "自定义", "期間指定", "Personalizado", "Personalizado", "Benutzerdefiniert", "Personnalisé", "Свой период", "Kustom")
        add("Charts",
            "图表", "グラフ", "Gráficas", "Gráficos", "Diagramme", "Graphiques", "Графики", "Grafik")
        add("Badges",
            "成就", "実績", "Logros", "Conquistas", "Erfolge", "Succès", "Награды", "Lencana")
        add("Speed",
            "速度", "速度", "Velocidad", "Velocidade", "Tempo", "Vitesse", "Скорость", "Kecepatan")
        add("Daily",
            "按日", "日別", "Diario", "Diário", "Täglich", "Par jour", "По дням", "Harian")
        add("Group",
            "群组", "グループ", "Grupo", "Grupo", "Gruppe", "Groupe", "Группа", "Grup")
        add("Me ({0})",
            "我（{0}）", "自分（{0}）", "Yo ({0})", "Eu ({0})", "Ich ({0})", "Moi ({0})", "Я ({0})", "Saya ({0})")
        add("Leave",
            "退出", "退出", "Salir", "Sair", "Verlassen", "Quitter", "Выйти", "Keluar")
        add("Close",
            "关闭", "閉じる", "Cerrar", "Fechar", "Schließen", "Fermer", "Закрыть", "Tutup")
        add("Route",
            "路线", "ルート", "Ruta", "Rota", "Strecke", "Parcours", "Маршрут", "Rute")
        add("START",
            "开始", "スタート", "INICIAR", "INICIAR", "START", "DÉMARRER", "СТАРТ", "MULAI")
        add("Allow",
            "允许", "許可する", "Permitir", "Permitir", "Erlauben", "Autoriser", "Разрешить", "Izinkan")
        add("{0} (me)",
            "{0}（我）", "{0}（自分）", "{0} (yo)", "{0} (eu)", "{0} (ich)", "{0} (moi)", "{0} (я)", "{0} (saya)")
        add("{0} runs",
            "{0} 次", "{0}回", "{0} carreras", "{0} corridas", "{0} Läufe", "{0} courses", "Пробежек: {0}", "{0} kali")
        add("Apply",
            "应用", "適用", "Aplicar", "Aplicar", "Anwenden", "Appliquer", "Применить", "Terapkan")
        add("About",
            "关于", "情報", "Acerca de", "Sobre", "Info", "À propos", "О приложении", "Tentang")
        add("Reset",
            "重置", "リセット", "Restablecer", "Redefinir", "Zurücksetzen", "Réinitialiser", "Сбросить", "Atur ulang")
        add("Rank",
            "排名", "順位", "Ranking", "Ranking", "Rangliste", "Classement", "Рейтинг", "Peringkat")
        add("Time",
            "时间", "時間", "Tiempo", "Tempo", "Zeit", "Temps", "Время", "Waktu")
        add("Pace",
            "配速", "ペース", "Ritmo", "Ritmo", "Pace", "Allure", "Темп", "Pace")
        add("Runs",
            "次数", "回数", "Carreras", "Corridas", "Läufe", "Courses", "Пробежки", "Jumlah lari")
        add("runs",
            "次", "回", "carreras", "corridas", "Läufe", "courses", "пробежек", "kali")
        add("Grid",
            "网格", "グリッド", "Cuadrícula", "Grade", "Raster", "Grille", "Сетка", "Kisi")
        add("Done",
            "完成", "完了", "Listo", "Concluir", "Fertig", "Terminé", "Готово", "Selesai")
        add("Sync",
            "同步", "同期", "Sincronización", "Sincronização", "Synchronisierung", "Synchronisation", "Синхронизация", "Sinkronisasi")
        add("Send",
            "发送", "送信", "Enviar", "Enviar", "Senden", "Envoyer", "Отправить", "Kirim")
        add("STOP",
            "结束", "終了", "DETENER", "PARAR", "STOPP", "ARRÊTER", "СТОП", "BERHENTI")
    }

    private fun part6() {
        add("Run",
            "跑步", "ラン", "Correr", "Correr", "Laufen", "Courir", "Бег", "Lari")
        add("min",
            "分", "分", "min", "min", "Min", "min", "мин", "mnt")
        add("Map",
            "地图", "地図", "Mapa", "Mapa", "Karte", "Carte", "Карта", "Peta")
        add("All",
            "全部", "すべて", "Todo", "Tudo", "Alle", "Tout", "Все", "Semua")
        add("Off",
            "关闭", "オフ", "No", "Não", "Aus", "Non", "Выкл.", "Mati")
        add("Me",
            "我", "自分", "Yo", "Eu", "Ich", "Moi", "Я", "Saya")
    }

}
