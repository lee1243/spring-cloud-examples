package cn.ianlee.example.spring.cloud.biz.a.service;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * @author Ian.Lee
 * @Description Feign Client接口定义
 * @date 2019/8/2 10:45 AM
 */
@FeignClient(name = "service-b")
public interface RemoteService {

    /**
     * 调用服务B的hello方法
     *
     * @return
     */
    @GetMapping("/hello")
    String sayHello();
}
