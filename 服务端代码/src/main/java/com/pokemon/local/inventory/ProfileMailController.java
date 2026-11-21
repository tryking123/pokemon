package com.pokemon.local.inventory;

import com.pokemon.local.inventory.api.vo.ProfileMailVO;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人信息页物品邮件入口。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:36
 */
@RestController
public class ProfileMailController {
    private final ProfileMailService mail;

    /**
     * 绑定个人邮件服务。
     * @param mail 邮件服务
     */
    public ProfileMailController(ProfileMailService mail) {
        this.mail = mail;
    }

    /**
     * 向本人当前角色投递物品。
     * @param authorization 本地登录请求头
     * @param vo 附件及请求标识
     * @return 邮件编号
     */
    @PostMapping("/api/game/profile/item-mail")
    public Object send(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                       @RequestBody ProfileMailVO vo) {
        return mail.send(authorization, vo);
    }
}
