LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := greekim

LOCAL_SRC_FILES := \
	net_bitquill_inputmethod_greek_BinaryDictionary.cpp \
	dictionary.cpp

include $(BUILD_SHARED_LIBRARY)

