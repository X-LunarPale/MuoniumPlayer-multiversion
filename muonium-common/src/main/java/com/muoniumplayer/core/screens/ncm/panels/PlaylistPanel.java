package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.PlaybackPresentation;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.svg.AnimatedIcons;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.CoverflowOverlay;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.PlaylistCoverHero;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.MpPartyClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 */
public class PlaylistPanel extends NCMPanel {

    public PlayList playList;
    private final boolean showBackButton;

    public PlaylistPanel(PlayList playlist) {
        this(playlist, true);
    }

    /** 供最近播放列表使用，其父级发现页面已负责导航。 */
    public PlaylistPanel(PlayList playlist, boolean showBackButton) {
        this.playList = playlist;
        this.showBackButton = showBackButton;
    }

    /**
     * 曲目行的进场错峰号。
     *
     * <p>号是在行<b>第一次被渲染</b>时领的，不是建页时发好的：{@code ScrollPanel} 不渲染视口外的行，
     * 建页就发号等于只有首屏那几行有动画，往下滚什么也看不见。这里只负责每帧把头一批重新计数，
     * 见 {@link PanelItemEntrance.RevealQueue}。</p>
     */
    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();

    /** 左上角那张大封面。返回时要问它现在在屏幕上的哪儿，好把接力交回主页卡片。 */
    private RoundedImageWidget heroCover;
    /** 「定位到正在播放」按钮；字形在子控件完成本帧布局后绘制。 */
    private RoundedButtonWidget btnLocate;

    @Override
    public void onRender(double mouseX, double mouseY) {
        this.revealQueue.beginFrame();
        this.revealQueue.setAnimationsSuppressed(this.musicsPanel != null && this.musicsPanel.isScrollActive());
        this.buildPendingMusicWidgets();
        this.autoScrollToPlayingIfNeeded();
        super.onRender(mouseX, mouseY);
    }

    @Override
    protected void afterRenderChildren(double mouseX, double mouseY) {
        super.afterRenderChildren(mouseX, mouseY);
        drawLocateIcon();
    }

    /** 使用本帧按钮的屏幕坐标，避免换行或缩放时字形落后底板一帧。 */
    private void drawLocateIcon() {
        RoundedButtonWidget button = this.btnLocate;
        if (button == null || button.isHidden()) return;
        float alpha = button.getWidgetAlpha();
        if (alpha <= .02f) return;
        int ink = button.isHovering()
                ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
        FoliaLucideIcons.draw(FoliaLucideIcons.LOCATE,
                button.getX() + button.getWidth() * .5,
                button.getY() + button.getHeight() * .5,
                14.0, RenderSystem.reAlpha(ink, alpha));
    }

    /**
     * 返回时把这张大封面交给接力：起点就是它此刻在屏幕上的矩形，落点是主页里同一个歌单的那张卡片。
     *
     * <p>只在目标确实是主页时调用 —— 主页可能把这张卡片滚在视口外，那样没人认领落点，
     * {@code PlaylistCoverHero} 会在第一帧自己收手，退化成纯交叉位移。</p>
     */
    public void handCoverBack() {
        if (this.heroCover == null) return;
        // 视口传 null：详情页这张大封面直接挂在面板上，不在任何滚动容器里，永远全须全尾。
        PlaylistCoverHero.begin(this, null, this.playList, this.playList.getCoverLocation(),
                this.heroCover.getX(), this.heroCover.getY(),
                this.heroCover.getWidth(), this.heroCover.getRadius(), null);
    }

    private TextFieldWidget tfSearch;
    private volatile boolean musicsLoading = true;
    private long buildGeneration;
    private int visibleMusicCount;

    /** 歌曲行分帧构建：先发布首屏，余下行在后续渲染帧里补齐，避免大歌单回调卡住主线程。 */
    private static final int MUSIC_WIDGET_BUILD_BATCH = 32;
    private static final long MUSIC_WIDGET_BUILD_BUDGET_NANOS = 750_000L;
    private ScrollPanel musicsPanel;
    private List<Music> pendingMusicBuild;
    private int pendingMusicIndex;
    private String musicFilterText = "";

    /**
     * 「定位到正在播放」：头部一枚 locate 按钮 + 打开页面时的一次自动滚。
     *
     * <p>按钮只在当前歌真的属于这张歌单时可见（按 {@link CloudMusic#playbackPresentation()} 的
     * 展示键比对，跨源替身也认）；点击把列表滚到那一行，行上本来就有 EQ 条与主题高亮，
     * 这里不再另做选中动画。</p>
     *
     * <p>自动滚走 {@code NcmSheet.scrollHeroIntoViewIfNeeded} 同一套「只一次」闸：曲目行是
     * 分帧批量建的，定位目标那行没建出来之前不动（offset 算出来必是错的）；等全量建完、
     * 打开后的头几帧里滚一次，用户随后手动滚动不受到任何约束。仅当打开的就是
     * {@code CloudMusic.currentPlaylistContext} 那张单时才触发——在别的歌单页上乱滚是惊吓。</p>
     */
    private boolean autoScrolledToPlaying;

    @Override
    public void onInit() {
        // onInit 也会在账户元数据（包括云盘 ID）
        // 异步到达时被调用。使其具备幂等性，以便刷新时替换
        // 旧行，而不是在第一份之上再叠加第二份。
        this.getChildren().clear();
        this.musicsLoading = true;
        this.musicsPanel = null;
        this.pendingMusicBuild = null;
        this.pendingMusicIndex = 0;
        this.musicFilterText = "";
        this.visibleMusicCount = 0;
        this.autoScrolledToPlaying = false;
        this.tfSearch = null;
        this.lastSize = -1;
        this.nextInfoRefreshAt = 0L;
        final long generation = ++this.buildGeneration;
        // onInit 会在账户元数据异步到达时重建整页：字段指着的旧按钮已随 getChildren().clear()
        // 脱树，不置空的话搜索模式页（不建按钮）会把字形画到脱离树的旧按钮坐标上。
        this.btnLocate = null;

        // 搜索结果页不放返回按钮：搜索是从左侧导航栏的搜索框直接进入的，右上角这枚返回键在那里
        // 没有对应的"上一页"，只会把内容区右上角挤掉一块。歌单详情、发现页曲目列表照旧保留。
        boolean backButtonAllowed = showBackButton && !playList.isSearchResultList();
        RoundedButtonWidget btnBack = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
        btnBack.setGlassPlate(GlassLook.Surface.PLAYER)
                .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        this.addChild(btnBack);
        btnBack.setShouldOverrideMouseCursor(true);
        btnBack.setBeforeRenderCallback(() -> {
            btnBack.setHidden(!backButtonAllowed);
            btnBack.setBounds(28, 22);
            btnBack.setPosition(Math.max(8, getWidth() - btnBack.getWidth() - 12), 8);
            btnBack.setRadius(6);
            btnBack.setColor(btnBack.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            btnBack.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        btnBack.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().navigateBack();
            return true;
        });

        // 列表跟随工具栏的实际行数让位，窄窗换行后不会盖住歌曲。
        final double[] headerHeight = new double[1];
        headerHeight[0] = 176.0;

        final PlaylistToolbarLayout toolbar = new PlaylistToolbarLayout();
        // 必须在渲染回调里判宽度：建页时面板还没有最终边界。

        if (!playList.isSearchMode()) {
            RoundedImageWidget cover = new RoundedImageWidget(this.playList.getCoverLocation(), 0, 0, 0, 0);

            cover.setPosition(24, 24);
            cover.setBounds(128, 128);
            cover.fadeIn();
            cover.setLinearFilter(true);

            this.addChild(cover);
            this.heroCover = cover;
            this.loadCover();

            cover.setBeforeRenderCallback(() -> {
                if (PlaylistCoverHero.consumeHandoff(this)) {
                    cover.setAlpha(1.0f);
                }
                cover.setRadius(4);
                // 窄内容区收成 96：判据每帧现读（见上方注释），跨过阈值当场切档。
                double coverSize = getWidth() < 400.0 ? 96.0 : 128.0;
                cover.setBounds(coverSize, coverSize);
                toolbar.reset(getWidth(), cover.getRelativeY() + cover.getHeight() + 12.0);
                headerHeight[0] = toolbar.bottom() + 12.0;
                // 每帧回报落点：接力封面要朝这里飞，而这一帧封面本身让位（纹理供给换成 null，
                // 控件照常跑回调），所以屏幕上任何时刻只有一张封面。
                PlaylistCoverHero.setTarget(this, this.playList, cover.getX(), cover.getY(),
                        cover.getWidth(), cover.getRadius(), null);
            });
            cover.setLocImg(() -> PlaylistCoverHero.coversYield(this) ? null : this.playList.getCoverLocation());

//        LabelWidget lblPlaylistName = new LabelWidget(playList.name, FontManager.pf);
            RoundedButtonWidget btnPlay = new RoundedButtonWidget("播放歌单", FontManager.pf16bold);
            // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
            btnPlay.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnPlay);

            btnPlay.setBeforeRenderCallback(() -> {
                placeHeaderAction(btnPlay, toolbar, headerHeight, 68.0, true);
                boolean ready = !this.musicsLoading && playList.musicsLoaded && !playList.getMusics().isEmpty();
                btnPlay.setAlpha(ready ? 1f : .45f);
                btnPlay.setClickable(ready);
                btnPlay.setRadius(3);
                btnPlay.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnPlay.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            btnPlay.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0 || this.musicsLoading || !playList.musicsLoaded) return false;
                List<Music> musics = playList.getMusics();
                if (musics == null || musics.isEmpty()) return false;
                CloudMusic.currentPlaylistContext = playList.isSearchMode() ? null : playList;
                CloudMusic.play(new ArrayList<>(musics), 0);
                return true;
            });


            // 「排进房间」= 把这一整张单子挂到一起听的队列后面。它只在<b>真的在一间房间里、
            // 而且这台服务端认识这个功能</b>时才出现（频道里不出现：全服共用的房间不该被一个人
            // 倒进一百首）。
            //
            // 按钮文字用「排进」而不是「播放」是有意的：它不会动正在播的那首歌，也不会插到别人
            // 前面 —— 说「播放」会让人以为按下去立刻换歌，而实际要到队列排空才听得见。
            //
            // 显隐走 alpha 而不是 setHidden：renderWidget 对隐藏的控件整帧跳过，挂在它身上的
            // beforeRenderCallback 也就不再执行 —— 一旦在建页那一瞬还没进房（房间状态晚到、
            // 重连的空档），setHidden(true) 之后这颗按钮就永远没有机会把自己显示回来，只能靠
            // 换一张歌单重建面板。alpha 每帧照算，进房后自己淡出来，正在翻的歌单、滚动位置
            // 一概不动。
            RoundedButtonWidget btnParty = new RoundedButtonWidget("排进房间", FontManager.pf16bold);
            final float[] partyFade = {MpPartyClient.playlistSupported() ? 1f : 0f};
            // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
            btnParty.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnParty);
            btnParty.setBeforeRenderCallback(() -> {
                boolean show = MpPartyClient.playlistSupported();
                partyFade[0] = Interpolations.interpolate(partyFade[0], show ? 1f : 0f, .25f);
                placeHeaderAction(btnParty, toolbar, headerHeight, 68.0, show || partyFade[0] > .02f);
                btnParty.setAlpha(partyFade[0]);
                btnParty.setClickable(show && MpPartyClient.canPlaylist());
                btnParty.setRadius(3);
                btnParty.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnParty.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            btnParty.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton == 0) {
                    // 曲目没在手上的单子（导入单、没点开过的账号单）由 requestPlaylist 内部先去加载，
                    // 加载回来再分帧发；这里不需要照抄「播放歌单」那一段 loadMusicsWithCallback。
                    MpPartyClient.requestPlaylist(playList);
                }
                return true;
            });


            RoundedButtonWidget btnCoverflow = new RoundedButtonWidget("Coverflow", FontManager.pf16bold);
            // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
            btnCoverflow.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnCoverflow);

            btnCoverflow.setBeforeRenderCallback(() -> {
                placeHeaderAction(btnCoverflow, toolbar, headerHeight, 68.0, true);
                btnCoverflow.setRadius(3);
                btnCoverflow.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                btnCoverflow.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            btnCoverflow.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    api.displayScreen(CoverflowOverlay.byPlaylist(playList));
                }

                return true;
            });

            RoundedButtonWidget btnSubscribe = new RoundedButtonWidget(
                    () -> playList.isSubscribed() ? "取消收藏" : "收藏歌单", FontManager.pf16bold);
                    // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
                    btnSubscribe.setGlassPlate(GlassLook.Surface.PLAYER)
                            .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnSubscribe);
            btnSubscribe.setBeforeRenderCallback(() -> {
                boolean canToggleSubscription = canToggleSubscription();
                placeHeaderAction(btnSubscribe, toolbar, headerHeight, 68.0, canToggleSubscription);
                btnSubscribe.setRadius(3);
                btnSubscribe.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                btnSubscribe.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });
            btnSubscribe.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0 || !canToggleSubscription()) {
                    return false;
                }
                if (!playList.isSubscribed()) {
                    playList.setSubscribed(true);
                    MultiThreadingUtil.runAsync(() -> playList.subscribe(true));
                    return true;
                }
                NCMScreen.getInstance().openConfirmation(
                        "取消收藏歌单？",
                        "取消后，该歌单将从“我的账号歌单”中移除。",
                        "取消收藏",
                        () -> {
                            playList.setSubscribed(false);
                            MultiThreadingUtil.runAsync(() -> playList.subscribe(false));
                        }
                );
                return true;
            });
            // 本地单与汽水自建云歌单的重命名动作进入同一条工具栏。
            RoundedButtonWidget btnRename = new RoundedButtonWidget("重命名", FontManager.pf16bold);
            // 贴在面板上，背后就是这一帧采进来的画面：给它一整块小玻璃（糊背景 + 薄折射 + 边光）。
            btnRename.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnRename);
            btnRename.setBeforeRenderCallback(() -> {
                boolean renamable = playList != null
                        && (playList.isLocalPlaylist() || ownQishuiCloudPlaylist());
                placeHeaderAction(btnRename, toolbar, headerHeight, 68.0, renamable);
                btnRename.setRadius(3);
                btnRename.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                btnRename.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });
            btnRename.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0) return false;
                if (playList != null && ownQishuiCloudPlaylist()) {
                    NCMScreen.getInstance().openQishuiCloudPlaylistRename(playList);
                } else {
                    NCMScreen.getInstance().openPlaylistRename(playList);
                }
                return true;
            });

            // 汽水自建云歌单才有「删除歌单」：真删上游，虚拟单（每日推荐/电台）与别家歌单不配。
            RoundedButtonWidget btnDeleteCloud = new RoundedButtonWidget("删除歌单", FontManager.pf16bold);
            btnDeleteCloud.setGlassPlate(GlassLook.Surface.PLAYER)
                    .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            this.addChild(btnDeleteCloud);
            btnDeleteCloud.setBeforeRenderCallback(() -> {
                placeHeaderAction(btnDeleteCloud, toolbar, headerHeight, 68.0, ownQishuiCloudPlaylist());
                btnDeleteCloud.setRadius(3);
                // 危险动作的红色与表单层的 BAD_COLOR(NcmSheet) 同值：全界面的「删除红」就一种。
                btnDeleteCloud.setColor(0xF1767D);
                btnDeleteCloud.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });
            btnDeleteCloud.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0 || !ownQishuiCloudPlaylist()) return false;
                NCMScreen.getInstance().openConfirmation(
                        "删除云歌单？",
                        "「" + playList.getName() + "」将从你的汽水账号中删除，客户端同步消失，不可恢复。",
                        "删除",
                        () -> MultiThreadingUtil.runAsync(() -> {
                            boolean ok = QishuiMusicService.deleteCloudPlaylist(
                                    playList.getPlatformPlaylistId());
                            MultiThreadingUtil.runOnMainThread(() -> {
                                DownloadDynamicIsland.showModelOperationResult("汽水云歌单",
                                        ok ? "已删除「" + playList.getName() + "」"
                                                : "删除失败：签名后端没通或上游拒绝",
                                        ok);
                                if (ok) {
                                    NCMScreen.getInstance().markDirty();
                                    NCMScreen.getInstance().setCurrentPanel(new HomePanel());
                                }
                            });
                        }));
                return true;
            });

            RoundedRectWidget searchBar = new RoundedRectWidget();
            this.addChild(searchBar);

            searchBar
                    .setShouldOverrideMouseCursor(true)
                    .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                        if (mouseButton == 0) {
                            if (!this.tfSearch.isFocused()) {
                                this.tfSearch.setFocused(true);
                                this.tfSearch.getTextField().lmbPressed = true;
                            }
                        }

                        return true;
                    })
                    .setBeforeRenderCallback(() -> {
                        placeHeaderAction(searchBar, toolbar, headerHeight, 116.0, true);
                        searchBar
                                .setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER))
                                .setRadius(4);
                    });

            RoundedRectWidget searchBarBg = new RoundedRectWidget();
            searchBar.addChild(searchBarBg);
            searchBarBg
                    .setClickable(false)
                    .setBeforeRenderCallback(() -> {
                        searchBarBg
                                .setMargin(.5)
                                // 只压这块底：搜索框里的文字与图标是 searchBar 的子控件，不归它管。
                                .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, .6f))
                                // 这一格本身是一块小玻璃（贴背景 + 薄折射 + 边缘高光）。
                                .setGlassPlate(GlassLook.Surface.PLAYER)
                                .setRadius(searchBar.getRadius() - .5);
                        searchBarBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
                    });

            LabelWidget lblSearchIcon = new LabelWidget("K", FontManager.music18);
            searchBar.addChild(lblSearchIcon);
            lblSearchIcon
                    .setClickable(false)
                    .setColor(hexColor(100, 100, 100))
                    .setBeforeRenderCallback(() -> lblSearchIcon
                            .centerVertically()
                            .setPosition(lblSearchIcon.getRelativeY(), lblSearchIcon.getRelativeY()));

            this.tfSearch = new TextFieldWidget(FontManager.pf14bold);
            this.tfSearch.setPlaceholder("筛选歌曲");
            searchBar.addChild(tfSearch);

            this.tfSearch.setOnKeyTypedCallback((character, keyCode) -> {
                if (this.tfSearch.isFocused()) {
                    if (keyCode == Keyboard.KEY_ESCAPE)
                        this.tfSearch.setFocused(false);


                    return true;
                }

                return false;
            });

            this.setOnKeyTypedCallback((character, keyCode) -> {

                if (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_G)) {
                    this.tfSearch.setFocused(true);
                    this.tfSearch.getTextField().selectAll();
                    return true;
                }

                return false;
            });

            // 「定位到正在播放」：当前歌属于这张歌单时才出现（搜索结果页没有这个概念，
            // 走 else 分支整颗不建）。用图标按钮而不是文字按钮：它要贴着搜索框右侧，
            // 文字版的「定位歌曲」会把整条按钮排挤出窄窗。
            // 图标不能走 RoundedButtonWidget 的文字标签——那会用 fontello 字体渲染
            // "locate-fixed" 这个字符串，fontello 里没有这个字形，画出来是空白。
            // 正确画法（NavigateBar 的 SidebarActionButton 同款）：按钮只当圆角底，
            // lucide 路径由 FoliaLucideIcons.draw 在回调里按中心点手画。
            if (!playList.isSearchMode()) {
                btnLocate = new RoundedButtonWidget("", FontManager.pf12);
                btnLocate.setGlassPlate(GlassLook.Surface.PLAYER)
                        .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
                this.addChild(btnLocate);
                final RoundedButtonWidget btnLocateRef = btnLocate;
                btnLocate.setBeforeRenderCallback(() -> {
                    // 每帧现判（当前歌可能切走），但只在「有/没有」跨越时才改可见性：
                    // 用 alpha 渐变而不是 setHidden——hidden 的控件回调整帧不跑，
                    // 一旦在起播前一帧藏掉就永远没有机会自己回来（与「排进房间」同一教训）。
                    boolean relevant = playlistContainsCurrentTrack();
                    float targetAlpha = relevant ? 1.0f : 0.0f;
                    float alpha = Interpolations.interpolate(btnLocate.getWidgetAlpha(), targetAlpha, .3f);
                    btnLocate.setAlpha(alpha);
                    btnLocate.setClickable(alpha > .5f);
                    if (relevant || alpha > .04f) {
                        PlaylistToolbarLayout.Slot slot = toolbar.next(28.0);
                        btnLocate.setBounds(slot.width(), slot.height());
                        btnLocate.setPosition(slot.x(), slot.y());
                        headerHeight[0] = toolbar.bottom() + 12.0;
                    } else {
                        btnLocate.setBounds(0, 0);
                    }
                    btnLocate.setRadius(6);
                    btnLocate.setColor(btnLocate.isHovering()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                    // 字形留到 afterRenderChildren，使用本帧更新后的矩形。
                });
                btnLocate.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (mouseButton != 0 || !playlistContainsCurrentTrack()) return false;
                    scrollToPlayingTrack();
                    return true;
                });
            }

            tfSearch.setBeforeRenderCallback(() -> {
                tfSearch.drawUnderline(false);
                tfSearch.setMargin(2);
                double xSpacing = lblSearchIcon.getRelativeX() + lblSearchIcon.getWidth() + 4;
                tfSearch.setBounds(xSpacing, tfSearch.getRelativeY(), tfSearch.getWidth() - xSpacing, tfSearch.getHeight());
                tfSearch.setColor(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                tfSearch.setDisabledTextColor(RenderSystem.reAlpha(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .4f));
            });

            User creator = this.playList.getCreator();
            boolean hasCreatorAvatar = creator != null
                    && creator.getAvatarUrl() != null
                    && !creator.getAvatarUrl().trim().isEmpty();
            RoundedImageWidget creatorAvatar = hasCreatorAvatar
                    ? new RoundedImageWidget(creator.getAvatarLocation(), 0, 0, 0, 0)
                    : null;

            if (creatorAvatar != null) {
                this.addChild(creatorAvatar);
                creatorAvatar.fadeIn();
                creatorAvatar.setLinearFilter(true);
                this.loadAvatar();
                creatorAvatar.setBeforeRenderCallback(() -> {
                    creatorAvatar.setBounds(16, 16);
                    creatorAvatar.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                            cover.getRelativeY() + cover.getHeight() - creatorAvatar.getHeight());
                    creatorAvatar.setRadius(7.25);
                });
            }

            // 没有创建者时退到「这张单来自哪个平台」。本地单不能这么退：它的 platform 字段顶着
            // NETEASE（那只是它被造出来时所处的世界），照抄就会在本机自建的单子上写「网易云」。
            String creatorName;
            if (playList.isLocalPlaylist()) {
                creatorName = "Mu本地";
            } else {
                creatorName = creator == null || creator.getName() == null || creator.getName().trim().isEmpty()
                        ? playList.getPlatform().getDisplayName()
                        : creator.getName();
            }
            LabelWidget lblCreator = new LabelWidget(creatorName, FontManager.pf16bold);
            this.addChild(lblCreator);

            lblCreator.setBeforeRenderCallback(() -> {
                if (creatorAvatar != null) {
                    lblCreator.setPosition(creatorAvatar.getRelativeX() + creatorAvatar.getWidth() + 4,
                            creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - lblCreator.getHeight() * .5);
                } else {
                    lblCreator.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                            cover.getRelativeY() + cover.getHeight() - lblCreator.getHeight());
                }
                lblCreator.setMaxWidth(Math.max(0, getWidth() - lblCreator.getRelativeX() - 12));
                lblCreator.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            LabelWidget lblPlaylistInfo = new LabelWidget(this::getPlayListInfo, FontManager.pf12);
            this.addChild(lblPlaylistInfo);

            lblPlaylistInfo.setBeforeRenderCallback(() -> {
                double creatorTop = creatorAvatar == null
                        ? lblCreator.getRelativeY()
                        : creatorAvatar.getRelativeY();
                lblPlaylistInfo.setMaxWidth(Math.max(0, getWidth() - (cover.getRelativeX() + cover.getWidth() + 12) - 12));
                lblPlaylistInfo.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                lblPlaylistInfo.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                        creatorTop - 8 - lblPlaylistInfo.getHeight());
                lblPlaylistInfo.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            });
            LabelWidget lblPlaylistName = new LabelWidget(playList.getName(), FontManager.pf32);
            this.addChild(lblPlaylistName);

            lblPlaylistName.setBeforeRenderCallback(() -> {
                lblPlaylistName.setFont(getWidth() < 280.0 ? FontManager.pf16bold : FontManager.pf24bold);
                lblPlaylistName.setMaxWidth(Math.max(0, getWidth() - (cover.getRelativeX() + cover.getWidth() + 12) - 12));
                lblPlaylistName.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                lblPlaylistName.setPosition(cover.getRelativeX() + cover.getWidth() + 12, lblPlaylistInfo.getRelativeY() - 4 - lblPlaylistName.getHeight());
                lblPlaylistName.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            // 非搜索模式的让位由 headerHeight 槽逐帧承载（封面回调写入），这里不再定格。
        } else {
            headerHeight[0] = 18.0;
        }

        Panel rwMusicsContainer = new Panel();
        final double bottomSafeInset = 8.0;
        final boolean searchLayout = playList.isSearchMode();

        this.addChild(rwMusicsContainer);

        rwMusicsContainer.setBeforeRenderCallback(() -> {
            // 让最后一个可见行与控制栏保持间距。嵌套的 ScrollPanel
            // 将歌曲行裁剪到这个缩小后的视口，而不是让文字贴边。
            // 非搜索模式读本帧工具栏高度，搜索模式恒 18。
            double offsetY = searchLayout ? 18.0 : headerHeight[0];
            rwMusicsContainer.setBounds(
                    Math.max(0.0, this.getWidth() - 36),
                    Math.max(0.0, this.getHeight() - offsetY - bottomSafeInset)
            );
            rwMusicsContainer.centerHorizontally();
            rwMusicsContainer.setPosition(rwMusicsContainer.getRelativeX(), offsetY);
        });

        final ScrollPanel musicsPanel = new ScrollPanel();
        this.musicsPanel = musicsPanel;

        rwMusicsContainer.addChild(musicsPanel);
        musicsPanel.setSpacing(0)
                .setUniformVerticalItemHeight(30.0);
        // 行与行挨着，就按<em>一块</em>玻璃收尾：这一组里每行登记自己的轮廓，画完子控件之后
        // 由一趟着色器按整张距离场画边（设置里「玻璃融合半径」推到十几才看得见变化，0 就是
        // 原来那样每行一圈）。为什么点名的这个容器而不是那一排行：融合必须括在<em>同一层裁剪</em>
        // 里，ScrollPanel 的 begin/endClip 正好就是那一层（见 AbstractWidget#renderWidget）。
        musicsPanel.setGlassFusion(GlassLook.Surface.PLAYER);

        musicsPanel.setBeforeRenderCallback(() -> musicsPanel.setMargin(0));

        LabelWidget empty = new LabelWidget(this::emptyMusicMessageText, FontManager.pf14bold);
        rwMusicsContainer.addChild(empty);
        empty.setClickable(false);
        empty.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        empty.setBeforeRenderCallback(() -> empty
                .setAlpha(isEmptyMusicView() ? 1f : 0f)
                .setMaxWidth(Math.max(0.0, rwMusicsContainer.getWidth() - 24.0))
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(12.0, 18.0));

        RoundedButtonWidget retry = new RoundedButtonWidget("重新加载", FontManager.pf14bold);
        rwMusicsContainer.addChild(retry);
        retry.setBeforeRenderCallback(() -> {
            boolean failed = isEmptyMusicView() && !playList.getMusicLoadError().isEmpty();
            retry.setAlpha(failed ? 1f : 0f);
            retry.setClickable(failed);
            retry.setBounds(72.0, 22.0);
            retry.setPosition(12.0, 44.0);
            retry.setRadius(4.0);
            retry.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            retry.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        retry.setOnClickCallback((x, y, button) -> {
            if (button != 0 || playList.getMusicLoadError().isEmpty()) return false;
            this.onInit();
            return true;
        });

        // 将加载反馈保持在被裁剪的歌曲列表上方。该控件会在
        // 异步请求完成后自行淡出。
        PanelLoadingWidget loadingWidget = new PanelLoadingWidget("正在加载曲目…");
        // 只把搜索结果页换成 search.svg 那枚取景框动画。同一份控件也服务歌单详情、主页、发现页
        // 与导航等待遮罩，那边等的是"一批曲目"而不是"一次搜索"，换成放大镜是在骗人。
        if (playList.isSearchResultList()) {
            loadingWidget.setAnimatedIcon(AnimatedIcons.search(), true);
        }
        this.addChild(loadingWidget);
        loadingWidget
                .setClickable(false)
                .setAlpha(0f)
                .setBeforeRenderCallback(() -> {
                    loadingWidget.setBounds(rwMusicsContainer.getRelativeX(), rwMusicsContainer.getRelativeY(),
                            rwMusicsContainer.getWidth(), rwMusicsContainer.getHeight());

                    float targetAlpha = musicsLoading ? 1f : 0f;
                    float nextAlpha = Interpolations.interpolate(loadingWidget.getWidgetAlpha(), targetAlpha, .24f);
                    loadingWidget.setAlpha(nextAlpha);
                    loadingWidget.setHidden(!musicsLoading && nextAlpha <= .015f);
                });

        playList.loadMusicsWithCallback(musics -> {
            if (generation != this.buildGeneration) return;
            // 不在网络回调里一次性构建整张歌单。先复制快照，再由 onRender 每帧补一小批，
            // 让首屏先出来，滚动和播放器其它动画不被几百个控件的构造阻塞。
            this.pendingMusicBuild = musics == null ? new ArrayList<>() : new ArrayList<>(musics);
            this.pendingMusicIndex = 0;
            this.visibleMusicCount = 0;
            this.playingRowMusicCount = -1;
            this.revealQueue.restart();
            this.musicsLoading = false;
            this.buildPendingMusicWidgets();
        });

        if (this.tfSearch != null) {
            this.tfSearch.setTextChangedCallback(text -> {
                this.musicFilterText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
                this.applyMusicFilter();
            });
        }
    }

    private static void placeHeaderAction(AbstractWidget<?> widget, PlaylistToolbarLayout toolbar,
                                          double[] headerHeight, double width, boolean visible) {
        widget.setAlpha(visible ? 1f : 0f);
        widget.setClickable(visible);
        if (!visible) {
            widget.setBounds(0, 0);
            return;
        }
        PlaylistToolbarLayout.Slot slot = toolbar.next(width);
        widget.setBounds(slot.width(), slot.height());
        widget.setPosition(slot.x(), slot.y());
        headerHeight[0] = toolbar.bottom() + 12.0;
    }

    /**
     * 每帧只构建一小批歌曲行。歌曲对象已经在后台线程准备好，真正昂贵的是 MusicWidget
     * 及其子控件树的创建和首次布局；把它摊到渲染帧之间可以避免大歌单打开/滚动时长帧。
     */
    private void buildPendingMusicWidgets() {
        if (this.musicsPanel == null || this.pendingMusicBuild == null) return;

        int total = this.pendingMusicBuild.size();
        int batchSize = com.muoniumplayer.core.screens.ncm.PlayerIslandTransition.needsFrameBudget()
                ? 2 : MUSIC_WIDGET_BUILD_BATCH;
        int end = Math.min(total, this.pendingMusicIndex + batchSize);
        if (this.pendingMusicIndex < end) {
            long startedAt = System.nanoTime();
            List<AbstractWidget<?>> batch = new ArrayList<>(end - this.pendingMusicIndex);
            while (this.pendingMusicIndex < end) {
                int index = this.pendingMusicIndex++;
                MusicWidget musicWidget = new MusicWidget(this.pendingMusicBuild.get(index), this.playList, index);
                musicWidget.setShouldOverrideMouseCursor(true);
                musicWidget.setEntranceAnimation(this.revealQueue);
                if (!this.musicFilterText.isEmpty()) {
                    musicWidget.setHidden(!matchesMusicFilter(musicWidget.music, this.musicFilterText));
                }
                if (!musicWidget.isHidden()) this.visibleMusicCount++;
                batch.add(musicWidget);
                if (System.nanoTime() - startedAt >= MUSIC_WIDGET_BUILD_BUDGET_NANOS) break;
            }
            this.musicsPanel.addChild(batch);
        }

        if (this.pendingMusicIndex >= total) {
            this.pendingMusicBuild = null;
        }
    }

    private boolean isEmptyMusicView() {
        return !this.musicsLoading && this.pendingMusicBuild == null && this.visibleMusicCount == 0;
    }

    private String emptyMusicMessageText() {
        if (!playList.getMusicLoadError().isEmpty()) return "曲目加载失败";
        if (!this.musicFilterText.isEmpty()) return "没有匹配的歌曲";
        return playList.isSearchResultList() ? "未找到歌曲" : "歌单暂无歌曲";
    }

    private void applyMusicFilter() {
        if (this.musicsPanel == null) return;
        // 无筛选时歌曲行恒为 30px，恢复等高快速路径；筛选会隐藏行，必须回到通用排版。
        this.musicsPanel.setUniformVerticalItemHeight(this.musicFilterText.isEmpty() ? 30.0 : -1.0);
        this.visibleMusicCount = 0;
        for (AbstractWidget<?> child : this.musicsPanel.getChildren()) {
            if (child instanceof MusicWidget) {
                MusicWidget widget = (MusicWidget) child;
                widget.setHidden(!matchesMusicFilter(widget.music, this.musicFilterText));
                if (!widget.isHidden()) this.visibleMusicCount++;
            }
        }
        this.musicsPanel.scrollToOffsetImmediately(0.0);
    }

    private static boolean matchesMusicFilter(Music music, String filter) {
        if (music == null || filter == null || filter.isEmpty()) return true;
        String name = music.getName() == null ? "" : music.getName().toLowerCase(Locale.ROOT);
        String translated = music.getTranslatedNames() == null ? "" : music.getTranslatedNames().toLowerCase(Locale.ROOT);
        if (name.contains(filter) || translated.contains(filter)) return true;
        if (music.getArtists() != null && music.getArtists().stream()
                .anyMatch(artist -> artist != null && artist.getName() != null
                        && artist.getName().toLowerCase(Locale.ROOT).contains(filter))) {
            return true;
        }
        return music.getAlbum() != null && music.getAlbum().getName() != null
                && music.getAlbum().getName().toLowerCase(Locale.ROOT).contains(filter);
    }

    /**
     * 仅外部网易云歌单支持订阅/取消订阅开关。
     * 当前账号创建的歌单本身已属于“我的歌单”，
     * 因此在那里显示“取消收藏”既具误导性又无实际作用。
     */
    private boolean canToggleSubscription() {
        if (playList == null) return false;
        // 本地单没有「收藏」这个动作可以对上游做：它的 platform 字段顶着 NETEASE（那只是它被造出来时
        // 所处的世界），拿一个负数 id 去调 /weapi/playlist/subscribe 是一次发给网易云的垃圾写请求。
        if (playList.isLocalPlaylist()) return false;
        if (playList.getPlatform() == MusicPlatform.KUGOU) {
            // 酷狗要登录才能收藏；而且「自己的歌单」不该显示收藏按钮——那边收藏会复制一份新歌单，
            // 点自己的歌单复制它没有意义。用户歌单在 platformPlaylistId 里带 #listid，用这个区分。
            return KuGouMusicService.isLoggedIn()
                    && KuGouMusicService.listIdOf(playList.getPlatformPlaylistId()).isEmpty();
        }
        if (playList.getPlatform() != MusicPlatform.NETEASE) {
            return false;
        }
        User creator = playList.getCreator();
        User currentUser = CloudMusic.profile;
        return creator == null || currentUser == null || creator.getId() != currentUser.getId();
    }

    /**
     * 打开的这张是不是<b>汽水自建云歌单</b>（acct: 前缀）：只有这类单子能改名、能删除、
     * 行内能移除歌曲。每日推荐（qsdaily:）与电台（radio:）是虚拟单，收藏歌单来自别人，
     * 都没有这些写权限。
     */
    private boolean ownQishuiCloudPlaylist() {
        return playList != null
                && playList.getPlatform() == MusicPlatform.QISHUI
                && playList.getPlatformPlaylistId() != null
                && playList.getPlatformPlaylistId().startsWith("acct:");
    }

    // 转子的实现已提到 PanelLoadingWidget：主页、发现页、导航等待遮罩用的是同一个控件。
    private String formatDuration(long totalMillis) {
        long totalSeconds = totalMillis / 1000;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();

        if (hours > 0) {
            sb.append(String.format(Locale.ROOT, "%02d时", hours));
        }

        if (minutes > 0) {
            sb.append(String.format(Locale.ROOT, "%02d分", minutes));
        }

        sb.append(String.format(Locale.ROOT, "%02d秒", seconds));

        return sb.toString();
    }

    String cached = "";
    int lastSize = -1;
    private long nextInfoRefreshAt;

    private String getPlayListInfo() {
        if (!playList.musicsLoaded)
            return playList.getMusicLoadError().isEmpty() ? "正在加载曲目" : "曲目加载失败";

        List<Music> musics = playList.musics;
        long now = System.currentTimeMillis();
        // 解码会补齐未知时长，即使曲数没变也要刷新总时长；避免每帧遍历大歌单。
        if (lastSize != musics.size() || now >= this.nextInfoRefreshAt) {
            lastSize = musics.size();
            this.nextInfoRefreshAt = now + 1_000L;
            if (musics.isEmpty()) {
                cached = "0首歌曲";
            } else {
                boolean durationKnown = musics.stream().allMatch(music -> music.getDuration() > 0L);
                cached = musics.size() + "首歌曲 · " + (durationKnown
                        ? this.formatDuration(musics.stream().mapToLong(Music::getDuration).sum())
                        : "时长待更新");
            }
        }

        return cached;
    }

    private void loadCover() {
        String coverUrl = this.playList.getCoverUrl();
        if (coverUrl == null || coverUrl.trim().isEmpty()) {
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        Location coverLoc = this.playList.getCoverLocation();
        if (textureManager.getTexture(coverLoc) != null) {
            return;
        }

        String requestUrl = imageRequestUrl(coverUrl, this.playList.getPlatform(), 256);
        Textures.downloadTextureAndLoadAsync(requestUrl, coverLoc);
    }

    private void loadAvatar() {
        User creator = this.playList.getCreator();
        if (creator == null || creator.getAvatarUrl() == null || creator.getAvatarUrl().trim().isEmpty()) {
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        Location avatarLoc = creator.getAvatarLocation();
        if (textureManager.getTexture(avatarLoc) != null) {
            return;
        }

        String requestUrl = imageRequestUrl(creator.getAvatarUrl(), this.playList.getPlatform(), 32);
        Textures.downloadTextureAndLoadAsync(requestUrl, avatarLoc);
    }

    static String imageRequestUrl(String url, MusicPlatform platform, int size) {
        String secure = url == null ? "" : url.replace("http://", "https://");
        if (secure.isEmpty() || platform != MusicPlatform.NETEASE) return secure;
        int fragmentAt = secure.indexOf('#');
        String fragment = fragmentAt < 0 ? "" : secure.substring(fragmentAt);
        String resource = fragmentAt < 0 ? secure : secure.substring(0, fragmentAt);
        int queryAt = resource.indexOf('?');
        String path = queryAt < 0 ? resource : resource.substring(0, queryAt);
        List<String> parameters = new ArrayList<>();
        if (queryAt >= 0) {
            for (String parameter : resource.substring(queryAt + 1).split("&")) {
                if (!parameter.isEmpty() && !parameter.equals("param") && !parameter.startsWith("param=")) {
                    parameters.add(parameter);
                }
            }
        }
        parameters.add("param=" + Math.max(1, size) + "y" + Math.max(1, size));
        return path + '?' + String.join("&", parameters) + fragment;
    }

    // ─────────────────────────── 定位到正在播放 ───────────────────────────

    /**
     * 当前展示曲目是否属于这张歌单。判据与行高亮完全同一条：
     * {@link CloudMusic#playbackPresentation()} 的展示键（含跨源替身），保证「按钮出现了」
     * 与「滚过去真的有一行在亮」永远一致——按钮消失而 EQ 条还亮着（或反过来）都是骗局。
     */
    private List<Music> playingRowMusics;
    private int playingRowMusicCount = -1;
    private String playingRowDisplayKey = "";
    private String playingRowPeerKey = "";
    private int cachedPlayingRow = -1;

    private boolean playlistContainsCurrentTrack() {
        return playingRowOffset() >= 0;
    }

    /** 定位按钮：把当前播放那行滚进视口并多留一点余量，让它不被列表上下沿贴边裁掉。 */
    private void scrollToPlayingTrack() {
        if (this.musicsPanel == null) return;
        if (!this.musicFilterText.isEmpty() && this.tfSearch != null) {
            this.tfSearch.setText("");
        }
        int row = playingRowOffset();
        if (row < 0) return;
        // 已清空筛选，行号与统一 30px 快照行高重新对应。
        double offset = row * 30.0;
        double viewport = this.musicsPanel.getHeight();
        if (viewport > 0) {
            offset = Math.max(0.0, offset - viewport * .5 + 15.0);
        }
        this.musicsPanel.scrollToOffsetImmediately(offset);
    }

    /** 当前展示曲目在本歌单里的行号；不在本单返回 -1。 */
    private int playingRowOffset() {
        PlaybackPresentation presentation = CloudMusic.playbackPresentation();
        String displayKey = presentation.displayKey();
        String peerKey = presentation.displayTrack == null ? "" : presentation.displayTrack.getCrossSourcePeerKey();
        // 显示路径不能调用会启动请求的 getMusics，否则失败后每帧自动重试并抹掉错误态。
        List<Music> musics = playList.musics;
        int count = musics == null ? 0 : musics.size();
        boolean unchanged = this.playingRowMusics == musics && this.playingRowMusicCount == count
                && this.playingRowDisplayKey.equals(displayKey) && this.playingRowPeerKey.equals(peerKey);
        if (unchanged && (this.cachedPlayingRow < 0
                || (this.cachedPlayingRow < count && presentation.isSelected(musics.get(this.cachedPlayingRow))))) {
            return this.cachedPlayingRow;
        }
        this.playingRowMusics = musics;
        this.playingRowMusicCount = count;
        this.playingRowDisplayKey = displayKey;
        this.playingRowPeerKey = peerKey;
        this.cachedPlayingRow = -1;
        if (displayKey.isEmpty()) return -1;
        for (int i = 0; i < count; i++) {
            if (presentation.isSelected(musics.get(i))) {
                this.cachedPlayingRow = i;
                break;
            }
        }
        return this.cachedPlayingRow;
    }

    /**
     * 打开页面时自动滚到正在播放的那一行（只一次）。三道闸缺一不可：
     * 只在「打开的就是当前队列来源的那张单」时触发、曲目行全量建完才动（分帧构建没到目标行时
     * offset 是错的）、布尔闸保证整个面板生命周期只滚这一回——用户随后往哪滚都随他。
     */
    private void autoScrollToPlayingIfNeeded() {
        if (this.autoScrolledToPlaying) return;
        if (this.pendingMusicBuild != null) return;   // 行还没建完
        if (this.musicsPanel == null || this.musicsPanel.getHeight() <= 1.0) return;
        if (this.musicsPanel.getChildren().isEmpty()) return;
        // 只认「这张单就是当前队列的来源」：正在放的是搜索队列/别的歌单时，打开这张单不滚。
        PlayList context = CloudMusic.currentPlaylistContext;
        boolean fromThisList = context != null && context == this.playList;
        this.autoScrolledToPlaying = true;   // 无论结论如何都只判一次，别每帧白扫
        if (!fromThisList) return;
        if (playingRowOffset() < 0) return;
        scrollToPlayingTrack();
    }

}
