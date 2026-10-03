package com.hypernexus.nit.engine

object LlamaEngine {
    init { System.loadLibrary("hypernexus_native") }
    external fun getNativeVersion(): String
    external fun initModel(modelPath: String): Boolean
    external fun generateResponse(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): String
    external fun isModelLoaded(): Boolean
    external fun getModelInfo(): String
    external fun embedText(text: String, maxDims: Int = 384): FloatArray
    external fun unloadModel()
    external fun calculateRSI(prices: DoubleArray, period: Int): DoubleArray
    external fun calculateMACD(prices: DoubleArray, fastPeriod: Int = 12, slowPeriod: Int = 26, signalPeriod: Int = 9): DoubleArray
    external fun calculateBollingerBands(prices: DoubleArray, period: Int = 20, stdDevMultiplier: Double = 2.0): DoubleArray
    external fun calculateEMA(prices: DoubleArray, period: Int): DoubleArray
    external fun calculateStochastic(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, kPeriod: Int = 14, dPeriod: Int = 3): DoubleArray
    external fun calculateATR(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int): Double
    external fun fuzzyScore(targetStr: String, candidateStr: String): Int
    external fun mergeLoRAWeights(baseGgufPath: String, deltaLoraPath: String, alpha: Float): Boolean
}
