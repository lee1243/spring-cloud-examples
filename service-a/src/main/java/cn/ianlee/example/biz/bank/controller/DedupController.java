package cn.ianlee.example.biz.bank.controller;

import cn.ianlee.example.biz.bank.common.Result;
import cn.ianlee.example.biz.bank.dedup.AntiDuplicate;
import cn.ianlee.example.biz.bank.dedup.AntiDuplicateTokenService;
import cn.ianlee.example.biz.bank.transfer.TransferRequest;
import cn.ianlee.example.biz.bank.transfer.TransferResponse;
import cn.ianlee.example.biz.bank.transfer.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 防重演示接口。演示顺序：
 * 1）GET /bank/dedup/token 领取令牌 → 2）POST /bank/dedup/submit 带请求头 X-Dedup-Token 提交，
 * 同一令牌第二次提交返回 BB0003（令牌已被消费）；
 * 3）POST /bank/dedup/window 不带令牌，靠时间窗口拦截，同参数不同流水号二次提交返回 BB0002
 */
@Slf4j
@RestController
@RequestMapping("/bank/dedup")
public class DedupController {

    private final AntiDuplicateTokenService tokenService;
    private final TransferService transferService;

    public DedupController(AntiDuplicateTokenService tokenService, TransferService transferService) {
        this.tokenService = tokenService;
        this.transferService = transferService;
    }

    /**
     * 申领一次性防重令牌
     */
    @GetMapping("/token")
    public Result<Map<String, Object>> applyToken(@RequestParam(defaultValue = "CUST001") String userId) {
        String token = tokenService.apply(userId);
        Map<String, Object> data = new HashMap<>(4);
        data.put("userId", userId);
        data.put("token", token);
        data.put("usage", "提交时放入请求头 X-Dedup-Token");
        return Result.ok(data, "令牌申领成功");
    }

    /**
     * 查看当前有效令牌，便于观察「一次性」效果
     */
    @GetMapping("/tokens")
    public Result<List<Map<String, Object>>> listTokens() {
        return Result.ok(tokenService.listValid(), "当前有效令牌");
    }

    /**
     * 令牌模式提交：拿到令牌的那一次提交才有效
     */
    @PostMapping("/submit")
    @AntiDuplicate(bizType = "dedup-token", requireToken = true)
    public Result<TransferResponse> submitWithToken(TransferRequest request) {
        return Result.ok(transferService.transfer(request));
    }

    /**
     * 窗口模式提交：不校验令牌，靠「用户 + URI + 参数指纹」在窗口期内拦截。
     * 注意 reqNo 被剔除出指纹，所以换流水号但内容相同的请求同样会被拦下
     */
    @PostMapping("/window")
    @AntiDuplicate(bizType = "dedup-window")
    public Result<TransferResponse> submitInWindow(TransferRequest request, HttpServletRequest servletRequest) {
        log.info("[防重] 窗口模式受理, reqNo={}, uri={}", request.getReqNo(), servletRequest.getRequestURI());
        return Result.ok(transferService.transfer(request));
    }
}
