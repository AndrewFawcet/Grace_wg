var z := "789"
print (z)
var objectXXX := object {
    var variableX := "123456"

    print ""
    print "before  variableZ"
    z := variableX
    print "after  variableZ"
    print ""

    method foo {
        print(self.variableX)
        self.variableX := (self.variableX) ++ "789"
    }
}
print "----------0-----------"
var objectYYY := object {
    var variableY := "Hello"
}
print "----------1-----------"
objectXXX.foo()
print "----------2-----------"
objectXXX.foo()
print "----------3-----------"
print (z)
print (objectYYY.variableY)