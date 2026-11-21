package com.pokemon.local.payment;

import com.pokemon.local.payment.api.vo.ConfirmGameOrderVO;
import com.pokemon.local.payment.api.vo.GetOrderIdVO;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unity 游戏内本地模拟充值
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:41
 */
@RestController
public class GamePaymentController {
    private final GamePaymentService payments;

    /**
     * 初始化游戏充值接口
     *
     * @param payments 角色模拟充值服务
     */
    public GamePaymentController(GamePaymentService payments) {
        this.payments = payments;
    }

    /**
     * 接收原 Unity WWWForm 请求的角色及商品字段
     *
     * @param accountId SDK 登录后保存的本地账号编号
     * @param realmId 本地区服编号
     * @param roleId 到账角色编号
     * @param appId 原 Unity 应用编号
     * @param channel 原 Unity 应用渠道
     * @param productId 原充值档位编号
     * @param request 当前回环 HTTP 请求
     * @return 原客户端期望的逗号分隔状态和待付订单
     */
    @PostMapping(value = "/getorderid", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = "text/plain;charset=UTF-8")
    public String legacyOrder(@RequestParam(value = "userid", required = false) String accountId,
                              @RequestParam(value = "serverid", required = false) String realmId,
                              @RequestParam(value = "uid", required = false) String roleId,
                              @RequestParam(value = "appname", required = false) String appId,
                              @RequestParam(value = "channel", required = false) String channel,
                              @RequestParam(value = "product_id", required = false) String productId,
                              HttpServletRequest request) {
        return payments.legacyOrder(new GetOrderIdVO(accountId, realmId, roleId, appId, channel, productId),
                request.getRemoteAddr());
    }

    /**
     * 为 Android 本地确认框读取已下单的服务端快照
     *
     * @param authorization 本地 HTTP 会话请求头
     * @param orderId 服务端订单编号
     * @param vo 当前角色、区服及档位
     * @return 当前模拟价格、奖励和结算状态
     */
    @PostMapping("/api/game/payments/orders/{orderId}/quote")
    public Object quote(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                        @PathVariable String orderId, @RequestBody ConfirmGameOrderVO vo) {
        return payments.quote(authorization, orderId, vo);
    }

    /**
     * 结算用户明确确认的本地模拟充值
     *
     * @param authorization 本地 HTTP 会话请求头
     * @param orderId 服务端订单编号
     * @param vo 当前角色、区服及档位
     * @return 已确认状态与角色当前钻石余额
     */
    @PostMapping("/api/game/payments/orders/{orderId}/confirm")
    public Object confirm(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                          @PathVariable String orderId, @RequestBody ConfirmGameOrderVO vo) {
        return payments.confirm(authorization, orderId, vo);
    }
}
