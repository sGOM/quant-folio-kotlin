package com.quantfolio.web.auth

import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.web.api.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.WebUtils

const val SESSION_COOKIE = "qf_session"

/** 컨트롤러 파라미터에 붙이면 세션 쿠키로 인증된 [User] 가 주입된다. 실패하면 401. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

@Component
class CurrentUserResolver(
    private val sessions: SessionStore,
    private val users: UserRepository,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) =
        parameter.hasParameterAnnotation(CurrentUser::class.java) && parameter.parameterType == User::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): User {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)!!
        val sid = WebUtils.getCookie(request, SESSION_COOKIE)?.value
        val userId = sessions.userId(sid) ?: throw unauthorized()
        return users.findById(userId).orElseThrow { unauthorized() }
    }

    private fun unauthorized() = ApiException(HttpStatus.UNAUTHORIZED, "자격 증명을 확인할 수 없습니다.")
}

@Configuration(proxyBeanMethods = false)
class CurrentUserConfig(private val resolver: CurrentUserResolver) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(resolver)
    }
}
