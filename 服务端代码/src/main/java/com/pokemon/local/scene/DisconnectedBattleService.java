package com.pokemon.local.scene;

import com.pokemon.local.arena.ArenaService;
import com.pokemon.local.arena.BountyService;
import com.pokemon.local.arena.DnfService;
import com.pokemon.local.arena.FeastService;
import com.pokemon.local.arena.MineService;
import com.pokemon.local.arena.RocketService;
import com.pokemon.local.arena.WorldBossService;
import com.pokemon.local.arena.ZodiacService;
import com.pokemon.local.dailydup.DailyDungeonService;
import com.pokemon.local.dojo.DojoCombatService;
import com.pokemon.local.dojo.DojoStationService;
import com.pokemon.local.eden.EdenEncounterService;
import com.pokemon.local.guild.GuildEncounterService;
import com.pokemon.local.home.OrangeService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.secret.SecretService;
import com.pokemon.local.space.SpaceService;
import com.pokemon.local.tower.TowerService;
import java.util.Collections;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 重登入服时按各玩法撤退规则结算无法恢复画面的旧战斗。
 *
 * @author tongruiyuan
 * @since 2026-11-20 22:28
 */
@Service
public class DisconnectedBattleService {
    private final RoleService roles;
    private final ArenaService arena;
    private final BountyService bounty;
    private final DnfService dnf;
    private final FeastService feast;
    private final MineService mine;
    private final RocketService rocket;
    private final WorldBossService worldBoss;
    private final ZodiacService zodiac;
    private final DailyDungeonService daily;
    private final DojoCombatService dojo;
    private final DojoStationService station;
    private final EdenEncounterService eden;
    private final GuildEncounterService guild;
    private final OrangeService orange;
    private final SecretService secret;
    private final SpaceService space;
    private final TowerService tower;

    /**
     * 绑定原玩法的撤退结算，沿用其计次、生命及进度规则。
     *
     * @param roles 角色锁
     * @param arena 竞技训练
     * @param bounty 悬赏首领
     * @param dnf 地下城
     * @param feast 节日场景遭遇
     * @param mine 矿洞争夺
     * @param rocket 火箭队
     * @param worldBoss 世界首领
     * @param zodiac 十二宫
     * @param daily 日常副本
     * @param dojo 道馆挑战
     * @param station 道馆驻防
     * @param eden 乐园遭遇
     * @param guild 公会遭遇
     * @param orange 橘子群岛
     * @param secret 秘境
     * @param space 时空副本
     * @param tower 精灵塔
     */
    public DisconnectedBattleService(RoleService roles, ArenaService arena, BountyService bounty, DnfService dnf,
            FeastService feast, MineService mine, RocketService rocket, WorldBossService worldBoss,
            ZodiacService zodiac, DailyDungeonService daily, DojoCombatService dojo, DojoStationService station,
            EdenEncounterService eden, GuildEncounterService guild, OrangeService orange, SecretService secret,
            SpaceService space, TowerService tower) {
        this.roles = roles;
        this.arena = arena;
        this.bounty = bounty;
        this.dnf = dnf;
        this.feast = feast;
        this.mine = mine;
        this.rocket = rocket;
        this.worldBoss = worldBoss;
        this.zodiac = zodiac;
        this.daily = daily;
        this.dojo = dojo;
        this.station = station;
        this.eden = eden;
        this.guild = guild;
        this.orange = orange;
        this.secret = secret;
        this.space = space;
        this.tower = tower;
    }

    /**
     * 在登录快照前结算中断战斗；可恢复开场的精灵旅行继续由原入口恢复。
     *
     * @param roleId 重新进入游戏的角色编号
     */
    @Transactional
    public void settle(String roleId) {
        roles.lockRole(roleId);
        if (bounty.active(roleId)) bounty.turn(roleId, 0, Collections.emptyList(), -1, true);
        if (mine.active(roleId)) mine.turn(roleId, 0, Collections.emptyList(), -1, true);
        if (feast.active(roleId)) feast.turn(roleId, 0, Collections.emptyList(), -1, true);
        if (worldBoss.active(roleId)) worldBoss.turn(roleId, 0, Collections.emptyList(), -1, true);
        if (arena.active(roleId)) arena.flee(roleId);
        if (rocket.active(roleId)) rocket.turn(roleId, 0, Collections.emptyList(), -1, true);
        if (dnf.active(roleId)) dnf.advance(roleId, 0, Collections.emptyList(), -1, true);
        if (zodiac.active(roleId)) zodiac.advance(roleId, 0, Collections.emptyList(), -1, true);
        if (daily.active(roleId)) daily.advance(roleId, 0, Collections.emptyList(), -1, true);
        if (station.active(roleId)) station.action(roleId, 0, Collections.emptyList(), -1, true);
        if (dojo.active(roleId)) dojo.flee(roleId);
        if (eden.active(roleId)) eden.advance(roleId, 0, Collections.emptyList(), -1, true);
        guild.abandonDisconnectedBattle(roleId);
        if (orange.active(roleId)) orange.advance(roleId, 0, Collections.emptyList(), -1, true);
        if (space.active(roleId)) space.advance(roleId, 0, Collections.emptyList(), -1, true);
        if (tower.active(roleId)) tower.advance(roleId, 0, Collections.emptyList(), -1, true);
        secret.advance(roleId, 0, Collections.emptyList(), -1, true);
    }
}
