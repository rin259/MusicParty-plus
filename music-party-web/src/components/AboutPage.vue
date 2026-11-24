<template>
  <div class="min-h-app w-full bg-medical-50 text-medical-900 lg:h-screen lg:min-h-0">
    <div class="mx-auto flex w-full max-w-6xl flex-col px-4 py-8 md:px-8 md:py-12 pt-[max(2rem,env(safe-area-inset-top))] pb-[max(2rem,env(safe-area-inset-bottom))] lg:h-full lg:min-h-0 lg:py-8">

      <header class="flex flex-shrink-0 items-end justify-between gap-4 border-b border-medical-200 pb-5">
        <div class="min-w-0">
          <div class="mb-3 h-3 w-3 bg-accent"></div>
          <h1 class="m-0 text-3xl font-bold tracking-tighter md:text-4xl">MUSIC PARTY</h1>
          <p class="mt-2 font-mono text-[10px] tracking-[0.24em] text-medical-500">
            ABOUT · MANUAL · CREDITS
          </p>
        </div>
        <button
            type="button"
            class="flex min-h-11 flex-shrink-0 items-center gap-2 border border-medical-200 bg-surface-1 px-3 font-mono text-[10px] tracking-[0.12em] text-medical-600 transition-colors hover:border-accent hover:text-accent-text"
            @click="close"
        >
          <X class="h-3.5 w-3.5" aria-hidden="true" />CLOSE
        </button>
      </header>

      <div class="mt-6 grid min-h-0 gap-6 lg:flex-1 lg:grid-cols-[220px_1fr]">
        <!-- 目录：桌面为竖排列表，移动端压缩为可横向滚动的胶囊，避免占满首屏 -->
        <div class="min-w-0 lg:flex lg:min-h-0 lg:flex-col">
          <nav aria-label="内容目录" class="min-w-0 lg:min-h-0 lg:overflow-y-auto lg:pr-1 mp-scroll">
            <h2 class="m-0 mb-2 font-mono text-[10px] tracking-[0.18em] text-medical-500 lg:mb-3">CONTENTS</h2>
            <ul class="m-0 flex list-none gap-1 overflow-x-auto p-0 pb-1 lg:block lg:space-y-1 lg:overflow-visible lg:pb-0 mp-scroll">
              <li v-for="group in toc" :key="group.id" class="flex-shrink-0 lg:block">
                <button
                    type="button"
                    class="flex min-h-9 w-full items-center gap-2 whitespace-nowrap border px-2 text-left font-mono text-[10px] tracking-[0.1em] transition-colors lg:border-0 lg:border-l-2 lg:px-2"
                    :class="activeId === group.id
                      ? 'border-accent bg-accent/5 text-accent-text lg:border-l-accent'
                      : 'border-medical-200 text-medical-600 hover:border-medical-300 hover:text-medical-900 lg:border-l-medical-200 lg:hover:border-l-medical-300'"
                    @click="jumpTo(group.id)"
                >{{ group.label }}</button>
              </li>
            </ul>
          </nav>

          <div class="mt-3 border border-medical-200 bg-surface-1 p-3 lg:mt-5">
            <div class="font-mono text-[9px] tracking-[0.14em] text-medical-500">REPOSITORY</div>
            <a
                :href="REPO_URL"
                target="_blank"
                rel="noopener noreferrer"
                class="mt-1.5 flex items-center gap-1.5 break-all font-mono text-[10px] text-accent-text hover:underline"
            >{{ REPO_LABEL }}<ExternalLink class="h-3 w-3 flex-shrink-0" aria-hidden="true" /></a>
            <p class="mt-2 text-[11px] leading-relaxed text-medical-600">欢迎提交 issue 与 pull request。</p>
          </div>
        </div>

        <!-- 正文 -->
        <div ref="scroller" class="min-w-0 lg:min-h-0 lg:overflow-y-auto lg:pr-2 mp-scroll">
          <div class="space-y-5">

            <!-- 贡献者 -->
            <section :id="'sec-credits'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <Users class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">制作与贡献 / CREDITS</h2>
              </div>
              <div class="space-y-3 p-4">
                <article class="border border-medical-200 bg-medical-50 p-3">
                  <div class="flex flex-wrap items-baseline gap-2">
                    <span class="text-sm font-bold">Asterism</span>
                    <span class="border border-accent/40 px-1 font-mono text-[9px] leading-4 text-accent-text">FORK 二次开发</span>
                  </div>
                  <dl class="mt-2 grid gap-1 font-mono text-[10px] text-medical-600">
                    <div class="flex gap-2"><dt class="w-12 shrink-0 text-medical-500">MAIL</dt><dd class="m-0 break-all">rin@rin.red</dd></div>
                    <div class="flex gap-2"><dt class="w-12 shrink-0 text-medical-500">QQ</dt><dd class="m-0">3184015596</dd></div>
                  </dl>
                  <p class="mt-2 text-[11px] leading-relaxed text-medical-600">
                    维护本仓库的队列调度、多源取流、管理终端与界面改造。
                  </p>
                </article>

                <article class="border border-medical-200 bg-medical-50 p-3">
                  <div class="flex flex-wrap items-baseline gap-2">
                    <span class="text-sm font-bold">WhEN</span>
                    <span class="border border-medical-300 px-1 font-mono text-[9px] leading-4 text-medical-500">参与贡献</span>
                  </div>
                  <dl class="mt-2 grid gap-1 font-mono text-[10px] text-medical-600">
                    <div class="flex gap-2"><dt class="w-12 shrink-0 text-medical-500">QQ</dt><dd class="m-0">3960003260</dd></div>
                  </dl>
                  <p class="mt-2 text-[11px] leading-relaxed text-medical-600">
                    贡献房间邮箱订阅与召唤、页头按钮、房间内切换与沉浸壁纸模式。
                  </p>
                </article>

                <p class="m-0 text-[11px] leading-relaxed text-medical-500">
                  本项目基于开源项目二次开发，遵循其原有许可。上游作者与各音乐平台无隶属关系。
                </p>
              </div>
            </section>

            <!-- 快速开始 -->
            <section :id="'sec-start'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <PlayCircle class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">快速开始 / GETTING_STARTED</h2>
              </div>
              <ol class="m-0 space-y-2 p-4 pl-4 text-[13px] leading-relaxed text-medical-700">
                <li><span class="font-mono text-[10px] text-accent-text">01</span> 打开页面，在右手边填写昵称。昵称留空或写成「游客」会被当作游客，游客不能点歌、投票或切歌。</li>
                <li><span class="font-mono text-[10px] text-accent-text">02</span> 在 <b>CREATE ROOM</b> 建房，或在 <b>JOIN BY CODE</b> 输入房间码／粘贴邀请链接进入别人的房间。房间码本身就是邀请凭证。</li>
                <li><span class="font-mono text-[10px] text-accent-text">03</span> 进入房间后点 <b>SEARCH</b>（或按 <kbd>Ctrl</kbd>/<kbd>⌘</kbd> + <kbd>K</kbd>）搜索歌曲，加入队列。</li>
                <li><span class="font-mono text-[10px] text-accent-text">04</span> 队列里的歌由服务器统一排程与播放，所有人听到的是同一首、同一进度。</li>
              </ol>
              <p class="m-0 border-t border-medical-100 bg-medical-50 px-4 py-2.5 text-[11px] leading-relaxed text-medical-600">
                首次进房会自动出现一次新手引导（聚光灯形式）。想重看：清空浏览器里 <code class="font-mono">mp_tutorial_done_v1</code> 后重新进入。
              </p>
            </section>

            <!-- 房间 -->
            <section :id="'sec-rooms'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <DoorOpen class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">房间 / ROOMS</h2>
              </div>
              <div class="space-y-3 p-4 text-[13px] leading-relaxed text-medical-700">
                <p class="m-0">房间彼此独立：队列、聊天、播放状态、在线列表都只在本房间内广播，不会串到别的房间。</p>
                <ul class="m-0 space-y-1.5 pl-4">
                  <li><b>公开</b>：出现在 ROOM DIRECTORY 的 OTHER ROOMS，任何人可进。</li>
                  <li><b>仅链接</b>（LINK ONLY）：不出现在目录，只认房间码或邀请链接。</li>
                  <li><b>密码房间</b>：进入时需输入密码，密码只在本设备记住。</li>
                  <li><b>主房间</b>（DEFAULT）：站点默认房间，不可删除。</li>
                </ul>
                <p class="m-0">房间目录每页 6 个，点 <b>LOAD MORE</b> 展开、<b>SHOW LESS</b> 收起；右上 <b>REFRESH</b> 重新拉取。</p>
                <p class="m-0 rounded-sm border border-medical-200 bg-medical-50 px-3 py-2 text-[11px] text-medical-600">
                  房主身份与「设备」绑定，存在浏览器 localStorage。清空浏览器存储会丢掉房主身份，请先确认没有未保存的改动。
                </p>
                <p class="m-0">进房后可用页头的 <b>SWITCH</b>／<b>换房</b> 按钮在不刷新的情况下切换房间；切换会断开当前连接并清空本地队列缓存。</p>
              </div>
            </section>

            <!-- 点歌 -->
            <section :id="'sec-queue'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <ListMusic class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">点歌与队列 / QUEUE</h2>
              </div>
              <div class="space-y-3 p-4 text-[13px] leading-relaxed text-medical-700">
                <p class="m-0">支持网易云音乐、哔哩哔哩、SoundCloud、YouTube Music 四个源。点搜索结果右侧的加号即入队；同一个源重复点同一首会被判重拒绝。</p>
                <ul class="m-0 space-y-1.5 pl-4">
                  <li><b>队列状态</b>：<code class="font-mono text-[11px]">WAITING</code> 排队中 → <code class="font-mono text-[11px]">PREPARING</code> 正在取流 → <code class="font-mono text-[11px]">READY</code> 可播 → <code class="font-mono text-[11px]">FAILED</code> 取流失败。</li>
                  <li><b>置顶</b>：悬停队列项，点向上箭头把这首歌提到下一位。</li>
                  <li><b>删除</b>：悬停后点删除；只能删自己点的歌（管理员例外）。</li>
                  <li>队列长度、每人点歌上限、历史容量由管理员在管理终端配置。</li>
                </ul>
                <p class="m-0">歌单与专辑可以整单导入，单次导入数量受「导入单次上限」限制；导入是异步的，进度会在搜索结果里显示。</p>
                <p class="m-0 rounded-sm border border-medical-200 bg-medical-50 px-3 py-2 text-[11px] text-medical-600">
                  哔哩哔哩的视频会取音频轨缓存为 m4a；多 P 视频只取第一 P。超过 10 分钟的视频不允许点播。
                </p>
              </div>
            </section>

            <!-- 播放控制 -->
            <section :id="'sec-playback'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <SlidersHorizontal class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">播放控制 / PLAYBACK</h2>
              </div>
              <div class="space-y-3 p-4 text-[13px] leading-relaxed text-medical-700">
                <p class="m-0"><b>暂停／播放、切歌、播放模式都是全局的</b>，会立刻影响房间内所有听众。操作前请确认房间里没有别人正在听。</p>
                <ul class="m-0 space-y-1.5 pl-4">
                  <li><b>顺序播放</b>：按队列顺序依次播放。</li>
                  <li><b>随机播放</b>：公平随机，保证每个人都点的歌都有相等机会被播到。</li>
                  <li><b>单曲循环／列表循环</b>：单曲结束后重播同一首／列表播完再从头来。</li>
                  <li><b>进度条</b>：可拖动跳转，也可用方向键以 5 秒为步长微调。</li>
                  <li><b>音量</b>：只影响本机，不广播。</li>
                </ul>
                <p class="m-0">中间封面可点击点赞；点赞标记会同步给房间内所有人。点播放条左侧的小封面可跳到歌曲的原站页面。</p>
                <p class="m-0">管理员可开启「权限锁」，锁住暂停／切歌／播放模式，防止误操作。</p>
              </div>
            </section>

            <!-- 投票切歌 -->
            <section :id="'sec-vote'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <Vote class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">投票切歌 / VOTE_SKIP</h2>
              </div>
              <div class="space-y-3 p-4 text-[13px] leading-relaxed text-medical-700">
                <p class="m-0">管理员开启后，切歌按钮变为投票：达到「比例 × 可投票人数」即自动跳过。</p>
                <ul class="m-0 space-y-1.5 pl-4">
                  <li><b>谁可以投</b>：在线、已填昵称（非游客）、且不是这首歌的点歌者。</li>
                  <li><b>可投票人数</b>：等于「在线非游客人数 − 1（点歌者）」。点歌者本人不在分母里，这是设计如此。</li>
                  <li><b>等待时间</b>：可配置。播放未满该秒数时不能投票，按钮上会显示倒计时。</li>
                  <li><b>再次点击可撤票</b>；每首歌开始时票数清零。</li>
                  <li><b>点歌者</b>切自己的歌是强制切歌，不受等待时间限制。</li>
                  <li>按钮角标显示 <code class="font-mono text-[11px]">当前票数/可投票人数</code>。</li>
                </ul>
                <p class="m-0 rounded-sm border border-medical-200 bg-medical-50 px-3 py-2 text-[11px] text-medical-600">
                  游客没有稳定身份，不计入分母也无法投票；若你无法投票，先确认昵称是否已填写。
                </p>
              </div>
            </section>

            <!-- 搜索与导入 -->
            <section :id="'sec-search'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <Search class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">搜索与导入 / SEARCH</h2>
              </div>
              <div class="space-y-3 p-4 text-[13px] leading-relaxed text-medical-700">
                <ul class="m-0 space-y-1.5 pl-4">
                  <li>顶部标签切换音乐源；一次只搜一个源，便于对照结果。</li>
                  <li>可以直接粘贴链接（歌曲／视频／歌单／专辑），会自动识别平台并解析。</li>
                  <li>搜索用户名可查看该作者的公开歌单。</li>
                  <li>歌单／专辑卡片上的导入按钮会把整单加入队列。</li>
                  <li>SoundCloud 可在搜索框内登录自己的账号，用于私有歌单与关注关系。</li>
                </ul>
                <p class="m-0">搜索结果长标题会自动截断，不会撑破弹窗；键盘 <kbd>Esc</kbd> 关闭搜索。</p>
              </div>
            </section>

            <!-- 聊天 -->
            <section :id="'sec-chat'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <MessageSquare class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">聊天 / CHAT</h2>
              </div>
              <div class="space-y-3 p-4 text-[13px] leading-relaxed text-medical-700">
                <p class="m-0">浮动聊天按钮可拖动；进入房间后按 <kbd>Esc</kbd> 优先关闭聊天，再按一次才退出壁纸模式。</p>
                <p class="m-0">系统消息（点歌、切歌、投票结果、导入结果）会在聊天栏里以不同等级展示，方便回溯发生了什么。</p>
                <p class="m-0">发言频率与聊天记录容量由管理员配置。</p>
              </div>
            </section>

            <!-- 邮件 -->
            <section :id="'sec-mail'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <Mail class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">邮箱订阅与召唤 / MAIL</h2>
              </div>
              <div class="space-y-3 p-4 text-[13px] leading-relaxed text-medical-700">
                <p class="m-0">站点配置了 SMTP 时，页头会出现 <b>SUB 订阅</b> 与 <b>CALL 召唤</b>。</p>
                <ul class="m-0 space-y-1.5 pl-4">
                  <li><b>订阅</b>：填邮箱 → 收到确认邮件 → 点确认后生效。</li>
                  <li><b>召唤</b>：房间内任何成员都可发起，订阅者会收到含房间链接的邮件。</li>
                  <li><b>冷却</b>：同一房间共享召唤冷却，房主可在房间设置里选 0–24 小时或跟随站点默认。</li>
                  <li><b>退订</b>：在订阅框再次填入同一邮箱即可退订。</li>
                </ul>
                <p class="m-0 text-[11px] text-medical-600">未配置 SMTP 时这两个入口不显示。</p>
              </div>
            </section>

            <!-- 外观 -->
            <section :id="'sec-appearance'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <Palette class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">外观与壁纸 / APPEARANCE</h2>
              </div>
              <div class="space-y-3 p-4 text-[13px] leading-relaxed text-medical-700">
                <p class="m-0">页头的调色板按钮打开「外观与主题」，所有改动即时预览，<b>点保存才写入本浏览器</b>。</p>
                <ul class="m-0 space-y-1.5 pl-4">
                  <li><b>明暗</b>：浅色／深色／跟随系统。</li>
                  <li><b>主色</b>：手动选色，或从当前封面取色（Monet）。封面取色需要浏览器支持 Canvas 取色，跨域封面会回退到默认色。</li>
                  <li><b>壁纸模式</b>：保留中央播放器、歌词与音量计，四边栏在指针靠近屏幕边缘时展开、移开后延时收回。死区与反应距离可逐边调，也可四边联动。</li>
                  <li><b>墙壁纸入口</b>：页头的图片按钮；从壁纸入口进房会自动进入沉浸模式，并记住上次的房间。</li>
                  <li><b>精简模式</b>：隐藏侧栏与页头，只留播放条，省屏幕与性能。</li>
                </ul>
                <p class="m-0 text-[11px] text-medical-600">设置只保存在本机，不随账号同步；换设备需要重新设置。</p>
              </div>
            </section>

            <!-- 细部规则 -->
            <section :id="'sec-rules'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <ScrollText class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">细则 / DETAILS</h2>
              </div>
              <dl class="m-0 divide-y divide-medical-100">
                <div v-for="rule in rules" :key="rule.term" class="grid gap-1 px-4 py-3 md:grid-cols-[150px_1fr] md:gap-4">
                  <dt class="m-0 font-mono text-[10px] tracking-[0.1em] text-medical-500">{{ rule.term }}</dt>
                  <dd class="m-0 text-[13px] leading-relaxed text-medical-700">{{ rule.text }}</dd>
                </div>
              </dl>
            </section>

            <!-- 免责 -->
            <section :id="'sec-legal'" class="overflow-hidden border border-medical-200 bg-surface-1 chamfer-br">
              <div class="flex items-center gap-2 bg-surface-strong p-3 text-white">
                <ShieldAlert class="h-4 w-4 text-accent-on-strong" aria-hidden="true" />
                <h2 class="m-0 text-xs font-bold uppercase tracking-widest font-mono">免责声明 / DISCLAIMER</h2>
              </div>
              <div class="space-y-2.5 p-4 text-[12px] leading-relaxed text-medical-600">
                <p class="m-0">本项目是自托管的多人在线听歌工具，仅供个人学习、研究与私人小范围使用，不提供任何音视频内容。</p>
                <p class="m-0">所有音频与元数据均来自第三方平台，版权归原权利人所有。使用者应自行确认其使用行为符合当地法律与各平台的服务条款；请勿用于商业传播或大规模再分发。</p>
                <p class="m-0">取流依赖第三方工具与平台接口，随时可能因上游变更而失效。作者不对可用性、完整性或任何间接损失作出保证。</p>
                <p class="m-0">部署者对自己实例上的内容、访问控制与数据留存负责。公开部署前请自行评估风险，并确保不侵犯他人权益。</p>
                <p class="m-0">本项目与网易云音乐、哔哩哔哩、SoundCloud、YouTube 及其关联公司无任何隶属或合作关系。</p>
              </div>
            </section>

            <footer class="border-t border-medical-200 pt-3 pb-2 font-mono text-[10px] leading-relaxed tracking-[0.12em] text-medical-500">
              MUSIC PARTY · SELF-HOSTED LISTENING ROOM ·
              <a :href="REPO_URL" target="_blank" rel="noopener noreferrer" class="text-accent-text hover:underline">CONTRIBUTE ON GITHUB</a>
            </footer>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import {
  Users, PlayCircle, DoorOpen, ListMusic, SlidersHorizontal, Vote, Search,
  MessageSquare, Mail, Palette, ScrollText, ShieldAlert, X, ExternalLink
} from 'lucide-vue-next';

const emit = defineEmits(['close']);

const REPO_URL = 'https://github.com/rin259/MusicParty-plus';
const REPO_LABEL = 'rin259/MusicParty-plus';

const toc = [
  { id: 'sec-credits', label: '制作与贡献' },
  { id: 'sec-start', label: '快速开始' },
  { id: 'sec-rooms', label: '房间' },
  { id: 'sec-queue', label: '点歌与队列' },
  { id: 'sec-playback', label: '播放控制' },
  { id: 'sec-vote', label: '投票切歌' },
  { id: 'sec-search', label: '搜索与导入' },
  { id: 'sec-chat', label: '聊天' },
  { id: 'sec-mail', label: '邮箱订阅' },
  { id: 'sec-appearance', label: '外观与壁纸' },
  { id: 'sec-rules', label: '细则' },
  { id: 'sec-legal', label: '免责声明' }
];

const rules = [
  { term: '播放同步', text: '播放进度由服务器统一维护，客户端每 250ms 校准一次。切后台时允许更大偏差，回到前台会重新对齐，因此切换标签页不会导致进度永久错位。' },
  { term: '本地缓存', text: '歌曲取流后缓存在服务器本地，同一首再次点播直接读缓存，不再访问上游；这既省时间也降低被风控的概率。' },
  { term: '取流失败', text: '失败会按退避策略重试；仍失败则标记 FAILED 并自动跳到下一首，不会让队列卡死。失败原因会在日志与聊天系统消息里给出。' },
  { term: '配额', text: '每人可同时在队列中的歌曲数、队列总长度、历史记录容量均可由管理员限制。达到上限时点歌会被拒绝并提示原因。' },
  { term: '防刷', text: '控制类操作（暂停／切歌／模式／投票）按用户各计 1 秒冷却。你和别人的操作互不影响，只有你自己连续点击才会被忽略。' },
  { term: '投票分母', text: '可投票人数 = 在线非游客人数 − 点歌者。游客与点歌者都不在分母内，因此分母通常比在线人数少 1 或更多。' },
  { term: '快捷键', text: 'Ctrl/⌘ + K 搜索；Esc 自上而下关闭最上层浮层（壁纸模式下最后一步才退出壁纸）。进度条聚焦后可用左右方向键 5 秒步进。' },
  { term: '浏览器要求', text: '需要支持 ES2020、WebSocket 与 Canvas 的现代浏览器。封面取色与壁纸模式在 Safari 上表现受限，建议 Chromium 系或 Firefox。' },
  { term: '数据存储', text: '昵称、设备身份、主题、壁纸与房间密码存在本机 localStorage；房间、队列与聊天记录保存在服务器，重启后恢复。' }
];

const scroller = ref(null);
const activeId = ref(toc[0].id);
let observer = null;

function jumpTo(id) {
  const root = scroller.value;
  if (!root) return;
  const target = root.querySelector('#' + id);
  if (!target) return;
  // On small screens the document scrolls instead of the panel, so measure the
  // target against the page rather than assuming an inner scroll container.
  const inner = root.scrollHeight > root.clientHeight + 4;
  const top = inner
    ? target.offsetTop - root.offsetTop
    : target.getBoundingClientRect().top + window.scrollY - 16;
  if (inner) root.scrollTo({ top, behavior: 'smooth' });
  else window.scrollTo({ top, behavior: 'smooth' });
  activeId.value = id;
}

function close() {
  emit('close');
}

onMounted(() => {
  const root = scroller.value;
  if (!root || typeof IntersectionObserver !== 'function') return;
  observer = new IntersectionObserver((entries) => {
    const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
    if (visible.length) activeId.value = visible[0].target.id;
  }, { root, rootMargin: '0px 0px -70% 0px', threshold: 0 });
  toc.forEach((group) => {
    const section = root.querySelector('#' + group.id);
    if (section) observer.observe(section);
  });
});

onBeforeUnmount(() => observer?.disconnect());
</script>
