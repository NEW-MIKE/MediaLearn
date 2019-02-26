package com.jadyn.mediakit.video.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.support.annotation.IntRange
import android.support.v4.util.LruCache
import android.text.TextUtils
import android.util.Log
import com.jadyn.mediakit.function.md5
import com.jadyn.mediakit.function.saveFrame
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.io.File

/**
 *@version:
 *@FileDescription: 帧的缓存管理类；内存缓存和磁盘缓存
 *@Author:jing
 *@Since:2019/2/20
 *@ChangeList:
 */
class FrameCache(@IntRange(from = 0, to = 3) maxSize: Int, private val dataSource: String) {

    private val TAG = "FrameCache $dataSource"

    private val lruCache by lazy {

        object : LruCache<String, Bitmap>(if (Runtime.getRuntime().availableProcessors() >= maxSize) maxSize
        else Runtime.getRuntime().availableProcessors()) {
            override fun sizeOf(key: String?, value: Bitmap?): Int {
                value?.apply {
                    return rowBytes * height / 1024
                }
                return super.sizeOf(key, value)
            }
        }
    }
    
    private val cacheDiskList = arrayListOf<Long>()
    
    init {
        //初始化的时候，把硬盘的已缓存的模板保存下来，用来判断
    }

    private val cacheDiskScheduler by lazy {
        Schedulers.io()
    }

    private val compositeDisposable by lazy {
        CompositeDisposable()
    }

    //获取目标帧缓存
    fun cacheFrame(target: Long, b: Bitmap) {
        Log.d(TAG, "cacheFrame target $target: ")
        cacheLru(target, b)
        cacheDisk(target, b)
    }
    
    fun release() {
        compositeDisposable.clear()
    }

    fun asyncGetTarget(target: Long, success: (Bitmap) -> Unit, failed: (Throwable) -> Unit) {
        getLruBitmap(target)?.apply{
            success.invoke(this)
            return
        }
        val disposable = Observable.fromCallable {
            val bitmap = blockingGetDiskCache(target) ?: throw Throwable("null disk")
            bitmap
        }.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    success.invoke(it)
                }, {
                    failed.invoke(it)
                })
        compositeDisposable.add(disposable)
    }

    fun getLruBitmap(target: Long): Bitmap? {
        return lruCache.get(getLruKey(target))
    }

    /*
    * 同步得到disk缓存
    * */
    fun blockingGetDiskCache(target: Long): Bitmap? {
        return BitmapFactory.decodeFile(getDiskKey(target))
    }

    private fun cacheLru(target: Long, b: Bitmap) {
        lruCache.put(getLruKey(target), b)
    }

    private fun cacheDisk(target: Long, b: Bitmap) {
        val fileName = getDiskKey(target)
        if (File(fileName).exists()) {
            return
        }
        val disposable = Observable.fromCallable {
            b.saveFrame(fileName)
            Log.d(TAG, "cache disk succeed $fileName: ")
        }.subscribeOn(cacheDiskScheduler).subscribe()
        compositeDisposable.add(disposable)
    }

    private fun getLruKey(target: Long) = TextUtils.concat(dataSource, target.toString()).toString()

    private fun getDiskKey(target: Long) = TextUtils.concat(
            Environment.getExternalStorageDirectory().path,
            "/frameCache/", md5(dataSource), "/$target.jpg").toString()
}