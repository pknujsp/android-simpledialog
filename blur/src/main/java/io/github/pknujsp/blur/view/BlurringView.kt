package io.github.pknujsp.blur.view

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.opengl.GLES32.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import androidx.core.view.drawToBitmap
import io.github.pknujsp.blur.BlurUtils.getCoordinatesInWindow
import io.github.pknujsp.blur.DirectBlurListener
import io.github.pknujsp.blur.R
import io.github.pknujsp.blur.processor.GlobalBlurProcessorImpl
import io.github.pknujsp.coroutineext.launchSafely
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.properties.Delegates


@Suppress("DEPRECATION")
class BlurringView private constructor(context: Context) : GLSurfaceView(context), DirectBlurListener, GLSurfaceView.Renderer {
  private var resizeRatio by Delegates.notNull<Double>()
  private var radius by Delegates.notNull<Int>()

  private var initialized = false

  private var contentView: View? = null
  private var blurredBitmap: Bitmap? = null
  private var window: Window? = null

  private var renderNum = 0L

  private val originalCoordinatesRect: Rect = Rect(0, 0, 0, 0)
  private val dstCoordinatesRect: Rect = Rect(0, 0, 0, 0)

  private var lastStartTime = System.currentTimeMillis()

  private val mutex = Mutex()

  private companion object {
    val mainScope = MainScope()
    @OptIn(DelicateCoroutinesApi::class) private val dispatcher = newFixedThreadPoolContext(2, "BlurringThreadPool")

    val blurProcessor: BlurringViewProcessor = GlobalBlurProcessorImpl

    const val vertexShader = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec2 a_texCoord;
        varying vec2 v_texCoord;
        void main() {
          gl_Position = uMVPMatrix * vPosition;
          v_texCoord = a_texCoord;
        }
        """

    const val fragmentShader = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D s_texture;
        void main() {
          gl_FragColor = texture2D(s_texture, v_texCoord);
        }
        """

    val vertices = floatArrayOf(
      -1.0f, -1.0f, 0f,  // bottom left
      1.0f, -1.0f, 0f,  // bottom right
      1.0f, 1.0f, 0f,  // top right
      -1.0f, 1.0f, 0f,  // top left
    )


    val uvs = floatArrayOf(
      0f, 1f,
      1f, 1f,
      1f, 0f,
      0f, 0f,
    )

    val indices = byteArrayOf(
      0, 1, 2,
      2, 3, 0,
    )

    val vertexBuffer = vertices.createBuffer(4)
    var uvBuffer = uvs.createBuffer(4)
    val indexBuffer: ByteBuffer = ByteBuffer.allocateDirect(indices.size).apply {
      put(indices)
      position(0)
    }

    var positionHandle: Int = 0
    var uvHandle: Int = 0
    var program: Int = 0
    val textures = IntArray(1)
    var mvpMatrixHandle: Int = 0

    val vpMatrix: FloatArray = FloatArray(16)
    val modelMatrix = FloatArray(16)
    val mvpMatrix = FloatArray(16)
  }

  private val onPreDrawListener = ViewTreeObserver.OnDrawListener {
    if (initialized) {
      mainScope.launchSafely(dispatcher) {
        contentView?.drawToBitmap()?.let { bitmap ->
          println(
            "getDrawingCache,${bitmap.generationId} $bitmap ${bitmap.isRecycled} ${bitmap.colorSpace} ${bitmap.isMutable} ${bitmap.byteCount} " + "${bitmap.rowBytes}",
          )
          mutex.withLock {
            ++renderNum
            lastStartTime = System.currentTimeMillis()
            blurredBitmap = bitmap
            requestRender()
          }
        }
      }.onException { _, t ->
        t.printStackTrace()
      }
    }
  }

  constructor(context: Context, resizeRatio: Double, radius: Int) : this(context) {
    this.resizeRatio = resizeRatio
    this.radius = radius

    id = R.id.blurring_view
    layoutParams = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    alpha = 0.3f

    setEGLContextClientVersion(3)
    setRenderer(this)
    renderMode = RENDERMODE_WHEN_DIRTY

    Matrix.setIdentityM(vpMatrix, 0)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    (context as Activity).window.let { window ->
      this.window = window

      window.decorView.findViewById<View>(android.R.id.content).let { contentView ->
        this.contentView = contentView
        contentView.viewTreeObserver.addOnDrawListener(onPreDrawListener)
        originalCoordinatesRect.set(contentView.getCoordinatesInWindow(window))
        dstCoordinatesRect.set(0, 0, originalCoordinatesRect.width(), originalCoordinatesRect.height())

        blurProcessor.initBlur(
          context,
          this@BlurringView,
          Size(originalCoordinatesRect.width(), originalCoordinatesRect.height()),
          radius,
          resizeRatio,
        )

        initialized = true
      }
    }
  }


  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    program = glCreateProgram().also {
      glAttachShader(it, loadShader(GL_VERTEX_SHADER, vertexShader.trimIndent()))
      glAttachShader(it, loadShader(GL_FRAGMENT_SHADER, fragmentShader.trimIndent()))

      glLinkProgram(it)
      val linkStatus = IntArray(1)
      glGetProgramiv(it, GL_LINK_STATUS, linkStatus, 0)
    }

    glClearColor(0f, 0f, 0f, 1f)
    glClearDepthf(1.0f)
    glEnable(GL_DEPTH_TEST)
    glDepthFunc(GL_LEQUAL)
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    glViewport(0, 0, width, height)

      positionHandle = glGetAttribLocation(program, "vPosition")
      mvpMatrixHandle = glGetUniformLocation(program, "uMVPMatrix")

      glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
      Matrix.setIdentityM(modelMatrix, 0)
      Matrix.setIdentityM(mvpMatrix, 0)

      uvHandle = glGetAttribLocation(program, "a_texCoord")
  }

  override fun onDrawFrame(gl: GL10?) {
    blurredBitmap?.run {
      glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

      glUseProgram(program)
      glEnableVertexAttribArray(positionHandle)
      glVertexAttribPointer(positionHandle, 3, GL_FLOAT, false, 12, vertexBuffer)

      glEnableVertexAttribArray(uvHandle)
      glVertexAttribPointer(uvHandle, 2, GL_FLOAT, false, 0, uvBuffer)

      Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
      glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

      glGenTextures(1, textures, 0)
      glActiveTexture(GL_TEXTURE0)
      glBindTexture(GL_TEXTURE_2D, textures[0])

      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)

      GLUtils.texImage2D(GL_TEXTURE_2D, 0, this, 0)

      glDrawElements(GL_TRIANGLES, indices.size, GL_UNSIGNED_BYTE, indexBuffer)
      glDeleteTextures(1, textures, 0)

      println("onDrawFrame : ${renderNum}, ${System.currentTimeMillis() - lastStartTime}ms")
    }
  }

  private fun loadShader(type: Int, shaderCode: String): Int = glCreateShader(type).also { shader ->
    glShaderSource(shader, shaderCode)
    glCompileShader(shader)
  }

  @Suppress("DEPRECATION")
  override fun onPause() {
    super.onPause()
    contentView?.viewTreeObserver?.removeOnDrawListener(onPreDrawListener)
    blurProcessor.onClear()
  }

  override fun onBlurred(bitmap: Bitmap?) {
    TODO("Not yet implemented")
  }
}

private fun FloatArray.createBuffer(capacity: Int): FloatBuffer = ByteBuffer.allocateDirect(size * capacity).run {
  order(ByteOrder.nativeOrder())
  asFloatBuffer().apply {
    put(this@createBuffer)
    position(0)
  }
}
