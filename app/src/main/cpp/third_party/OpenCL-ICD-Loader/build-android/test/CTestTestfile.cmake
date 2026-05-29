# CMake generated Testfile for 
# Source directory: /home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/test
# Build directory: /home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/build-android/test
# 
# This file includes the relevant testing commands required for 
# testing this directory and lists subdirectories to be tested as well.
add_test(opencl_icd_loader_test "/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/build-android/icd_loader_test")
set_tests_properties(opencl_icd_loader_test PROPERTIES  ENVIRONMENT "OCL_ICD_FILENAMES=/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/build-android/libOpenCLDriverStub.so" WORKING_DIRECTORY "/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/build-android" _BACKTRACE_TRIPLES "/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/test/CMakeLists.txt;17;add_test;/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/test/CMakeLists.txt;0;")
add_test(cllayerinfo_test "/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/build-android/cllayerinfo")
set_tests_properties(cllayerinfo_test PROPERTIES  ENVIRONMENT "OPENCL_LAYERS=/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/build-android/test/layer/libPrintLayer.so" WORKING_DIRECTORY "/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/build-android" _BACKTRACE_TRIPLES "/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/test/CMakeLists.txt;23;add_test;/home/j/AndroidStudioProjects/FancyAi/app/src/main/cpp/third_party/OpenCL-ICD-Loader/test/CMakeLists.txt;0;")
subdirs("log")
subdirs("driver_stub")
subdirs("loader_test")
subdirs("layer")
