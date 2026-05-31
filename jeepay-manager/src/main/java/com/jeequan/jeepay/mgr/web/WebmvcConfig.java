/*
 * Copyright (c) 2021-2031, 河北计全科技有限公司 (https://www.jeequan.com & jeequan@126.com).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jeequan.jeepay.mgr.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/*
* webmvc配置
*
* @author terrfly
* @site https://www.jeequan.com
* @date 2021/6/8 17:12
*/
@Configuration
public class WebmvcConfig implements WebMvcConfigurer {

    @Autowired
    private ApiResInterceptor apiResInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiResInterceptor);
    }

    /**
     * 跨站托管收银台静态资源
     * - /cashier/index.html /cashier/fingerprint.js → classpath:/static/cashier/
     * - /cashier/{token}     → 转发到 index.html（前端从 URL 取 token）
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/cashier/**")
                .addResourceLocations("classpath:/static/cashier/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // /cashier/<token> 一律走 index.html（fingerprint.js 是真实文件优先匹配 ResourceHandler）
        registry.addViewController("/cashier/{token:[A-Za-z0-9_-]{16,64}}")
                .setViewName("forward:/cashier/index.html");
    }
}
