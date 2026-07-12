package com.example.magicpinao

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.magicpinao.core.auth.AuthManager
import com.example.magicpinao.ui.screens.auth.LoginScreen
import com.example.magicpinao.ui.screens.auth.RegisterScreen
import com.example.magicpinao.ui.screens.calibration.CalibrationScreen
import com.example.magicpinao.ui.screens.home.HomeScreen
import com.example.magicpinao.ui.screens.profile.ProfileScreen
import com.example.magicpinao.ui.viewmodel.MainViewModel
import com.example.magicpinao.ui.theme.MagicpinaoTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File

class MainActivity : ComponentActivity() {

    sealed class Screen(val route: String) {
        object Login : Screen("login")
        object Register : Screen("register")
        object Home : Screen("home")
        object Calibration : Screen("calibration")
        object Profile : Screen("profile")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化AuthManager
        AuthManager.init(applicationContext)

        setContent {
            MagicpinaoTheme {
                val viewModel: MainViewModel by viewModels()
                val navController = rememberNavController()
                val vipRecords by viewModel.vipRecords.collectAsState()

                // 判断起始页面
                val startDestination = if (AuthManager.isLoggedIn) {
                    Screen.Home.route
                } else {
                    Screen.Login.route
                }

                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 登录页面
                    composable(Screen.Login.route) {
                        LoginScreen(
                            onLoginSuccess = {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            },
                            onNavigateToRegister = {
                                navController.navigate(Screen.Register.route)
                            },
                            onLogin = { account, password ->
                                suspendCancellableCoroutine { cont ->
                                    viewModel.login(account, password) { success, msg ->
                                        if (success) {
                                            cont.resume(Result.success(msg), null)
                                        } else {
                                            cont.resume(Result.failure(Exception(msg)), null)
                                        }
                                    }
                                }
                            }
                        )
                    }

                    // 注册页面
                    composable(Screen.Register.route) {
                        RegisterScreen(
                            onRegisterSuccess = {
                                Toast.makeText(this@MainActivity, "注册成功，请登录", Toast.LENGTH_SHORT).show()
                                navController.popBackStack()
                            },
                            onNavigateBack = {
                                navController.popBackStack()
                            },
                            onRegister = { account, nickname, password, inviteCode ->
                                suspendCancellableCoroutine { cont ->
                                    viewModel.register(account, nickname, password, inviteCode) { success, msg ->
                                        if (success) {
                                            cont.resume(Result.success(msg), null)
                                        } else {
                                            cont.resume(Result.failure(Exception(msg)), null)
                                        }
                                    }
                                }
                            }
                        )
                    }

                    // 主页
                    composable(Screen.Home.route) {
                        HomeScreen(
                            modifier = Modifier.fillMaxSize(),
                            viewModel = viewModel,
                            onMusicClick = { music ->
                                viewModel.playMusic(music.id)
                            },
                            onCloudMusicClick = { music ->
                                viewModel.playCloudMusic(music)
                            },
                            onCalibrationClick = {
                                navController.navigate(Screen.Calibration.route)
                            },
                            onNavigateToProfile = {
                                navController.navigate(Screen.Profile.route)
                            }
                        )
                    }

                    // 标定页面
                    composable(Screen.Calibration.route) {
                        CalibrationScreen(
                            onBackClick = {
                                navController.popBackStack()
                            }
                        )
                    }

                    // 个人中心
                    composable(Screen.Profile.route) {
                        ProfileScreen(
                            onNavigateBack = {
                                // 显式弹回 Home 路由
                                navController.popBackStack(Screen.Home.route, false)
                            },
                            onLogout = {
                                suspendCancellableCoroutine { cont ->
                                    viewModel.logout { success, msg ->
                                        // 无论服务端是否成功，都清除本地登录状态
                                        if (success) {
                                            navController.navigate(Screen.Login.route) {
                                                popUpTo(0) { inclusive = true }
                                            }
                                            cont.resume(Result.success(msg), null)
                                        } else {
                                            AuthManager.clearLogin()
                                            navController.navigate(Screen.Login.route) {
                                                popUpTo(0) { inclusive = true }
                                            }
                                            cont.resume(Result.success(""), null)
                                        }
                                    }
                                }
                            },
                            onUploadAvatar = { file ->
                                suspendCancellableCoroutine { cont ->
                                    viewModel.uploadAvatar(file) { success, msg ->
                                        if (success) {
                                            cont.resume(Result.success(msg), null)
                                        } else {
                                            cont.resume(Result.failure(Exception(msg)), null)
                                        }
                                    }
                                }
                            },
                            onActivateVip = { code ->
                                suspendCancellableCoroutine { cont ->
                                    viewModel.activateVipCode(code) { success, msg ->
                                        if (success) {
                                            cont.resume(Result.success(msg), null)
                                        } else {
                                            cont.resume(Result.failure(Exception(msg)), null)
                                        }
                                    }
                                }
                            },
                            onResetPassword = { account, newPwd ->
                                suspendCancellableCoroutine { cont ->
                                    viewModel.resetPassword(account, newPwd) { success, msg ->
                                        if (success) {
                                            cont.resume(Result.success(msg), null)
                                        } else {
                                            cont.resume(Result.failure(Exception(msg)), null)
                                        }
                                    }
                                }
                            },
                            vipRecords = vipRecords,
                            loadVipRecords = { viewModel.loadVipRecords() }
                        )
                    }
                }
            }
        }
    }
}
